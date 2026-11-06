package win.furryhook.utils.combat.neuro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.furryhook.utils.ConfigIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Everything the player has taught the Neuro rotation, plus the lookup that turns it back
 * into aim movement.
 *
 * <p>Two views of the same swings are kept side by side. The {@link #samples} list holds
 * them in order, which is what disk storage and the sequential replay mode need. The grid
 * indexes every single frame by its angular error, which is what the k-nearest-neighbour
 * lookup needs, because scanning hundreds of thousands of frames every tick on the game
 * thread is not an option.
 *
 * <p>The grid is what keeps the lookup flat as the dataset grows. Frames cluster hard
 * around zero error — that is where a fight spends most of its time — so cells there would
 * swallow most of the dataset and a "local" scan would degenerate into a full one. Each
 * cell therefore keeps at most {@link #CELL_CAPACITY} frames chosen by reservoir sampling:
 * an unbiased random subset of everything that landed in it, so a query stays bounded at
 * roughly nine cells' worth of candidates whether the dataset holds a hundred swings or a
 * hundred thousand.
 */
public final class NeuroDataset {
    public static final NeuroDataset INSTANCE = new NeuroDataset();
    public static final String DEFAULT_NAME = "default";
    /** Ceiling on the neighbour count a caller may ask for. */
    public static final int MAX_NEIGHBOURS = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger(NeuroDataset.class);
    private static final String DIRECTORY = "neuro";
    private static final String EXTENSION = ".json";
    private static final int FORMAT = 2;
    private static final String NAME_PATTERN = "[A-Za-z0-9_-]{1,32}";

    /** Oldest swings fall out past this; twenty thousand is roughly 25 MB in memory. */
    private static final int MAX_SAMPLES = 20_000;
    /** Trimming rebuilds the index, so it is done in batches rather than one swing at a time. */
    private static final int TRIM_BATCH = 512;

    private static final int CELL_CAPACITY = 64;
    private static final float CELL_YAW = 6.0F;
    private static final float CELL_PITCH = 5.0F;
    private static final int FRAME_BITS = 8;
    private static final int FRAME_MASK = 0xFF;

    /** Keeps a perfectly matching frame from taking the entire weight of a blend. */
    private static final float WEIGHT_EPSILON = 0.05F;

    private final List<NeuroSample> samples = new ArrayList<>();
    private final Map<Long, Cell> grid = new HashMap<>();

    private int frameCount;
    private String activeName = DEFAULT_NAME;
    private boolean dirty;
    private List<String> cachedNames;

    private NeuroDataset() {
    }

    public int sampleCount() {
        return this.samples.size();
    }

    public int frameCount() {
        return this.frameCount;
    }

    public String activeName() {
        return this.activeName;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public boolean isEmpty() {
        return this.samples.isEmpty();
    }

    public void add(NeuroSample sample) {
        if (sample == null) {
            return;
        }
        this.samples.add(sample);
        indexSample(this.samples.size() - 1, sample);
        this.frameCount += sample.frames();
        this.dirty = true;
        trim();
    }

    /**
     * Drops everything held in memory. The file on disk is deliberately left alone and the
     * dirty flag is cleared with it, so an autosave cannot quietly turn a mistaken wipe
     * into a permanent one — only an explicit save overwrites the file.
     */
    public void clear() {
        this.samples.clear();
        this.grid.clear();
        this.frameCount = 0;
        this.dirty = false;
    }

    /**
     * Weighted average of the movement the player produced in the {@code neighbours} most
     * similar situations, or null when the dataset has nothing comparable on file.
     *
     * <p>{@code variation} leans the result toward one randomly drawn neighbour (drawn in
     * proportion to its weight) instead of the smooth average, because a pure mean of
     * several traces is steadier than any single human trace ever was.
     */
    public float[] predict(NeuroQuery query, int neighbours, float variation, float duelBias) {
        if (this.samples.isEmpty()) {
            return null;
        }

        int wanted = Math.clamp(neighbours, 1, MAX_NEIGHBOURS);
        float[] bestDistance = new float[wanted];
        int[] bestEntry = new int[wanted];
        Arrays.fill(bestDistance, Float.MAX_VALUE);
        Arrays.fill(bestEntry, -1);

        int found = collect(query, 1, bestDistance, bestEntry, duelBias);
        if (found == 0) {
            // Nothing in the immediate neighbourhood; widen once before giving up so an
            // unusual angle still gets an answer when the dataset is thin.
            found = collect(query, 3, bestDistance, bestEntry, duelBias);
        }
        if (found == 0) {
            return null;
        }

        float totalWeight = 0.0F;
        float yawSum = 0.0F;
        float pitchSum = 0.0F;
        for (int index = 0; index < found; index++) {
            float weight = 1.0F / (bestDistance[index] + WEIGHT_EPSILON);
            totalWeight += weight;
            yawSum += weight * value(bestEntry[index], NeuroSample.DELTA_YAW);
            pitchSum += weight * value(bestEntry[index], NeuroSample.DELTA_PITCH);
        }
        if (totalWeight <= 0.0F) {
            return null;
        }

        float yaw = yawSum / totalWeight;
        float pitch = pitchSum / totalWeight;

        float lean = Mth.clamp(variation, 0.0F, 1.0F);
        if (lean > 0.0F && found > 1) {
            int picked = pick(bestDistance, found, totalWeight);
            yaw = Mth.lerp(lean, yaw, value(bestEntry[picked], NeuroSample.DELTA_YAW));
            pitch = Mth.lerp(lean, pitch, value(bestEntry[picked], NeuroSample.DELTA_PITCH));
        }

        return new float[]{yaw, pitch};
    }

    /**
     * The recorded swing whose opening frame best matches {@code query}, for the replay
     * mode. Whole swings are compared by their first frame only: replay commits to the
     * entire trace, so what matters is how it started.
     */
    public NeuroSample nearestSample(NeuroQuery query, float duelBias, boolean hitsOnly) {
        NeuroSample best = null;
        float bestDistance = Float.MAX_VALUE;
        for (NeuroSample sample : this.samples) {
            // Реплей проигрывает трассу целиком, поэтому имеет смысл выбирать только из
            // тех, что кончились ударом: замах, который никуда не привёл, повторять незачем.
            if (hitsOnly && !sample.label().isHit()) {
                continue;
            }
            float distance = query.distanceSqr(sample.data(), 0, duelBias);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sample;
            }
        }
        if (best == null && hitsOnly) {
            return nearestSample(query, duelBias, false);
        }
        return best;
    }

    /** Сколько замахов кончились ударом. */
    public int hitCount() {
        int count = 0;
        for (NeuroSample sample : this.samples) {
            if (sample.label().isHit()) {
                count++;
            }
        }
        return count;
    }

    /** Сколько замахов сняты один на один. */
    public int duelCount() {
        int count = 0;
        for (NeuroSample sample : this.samples) {
            if (sample.label().duel()) {
                count++;
            }
        }
        return count;
    }

    /** Средняя ровность ведения по всему датасету, 0..1 — то, насколько плавно водит рука. */
    public float averageSmoothness() {
        if (this.samples.isEmpty()) {
            return 0.0F;
        }
        float total = 0.0F;
        for (NeuroSample sample : this.samples) {
            total += sample.label().smoothness();
        }
        return total / this.samples.size();
    }

    public boolean save(String name) {
        Path path = pathFor(name);
        if (path == null) {
            return false;
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT);
        root.addProperty("saved", System.currentTimeMillis());
        root.addProperty("frames", this.frameCount);

        JsonArray array = new JsonArray();
        for (NeuroSample sample : this.samples) {
            JsonObject entry = new JsonObject();
            entry.addProperty("frames", sample.frames());
            // Ширина кадра пишется в файл: следующее расширение полей сможет дочитать это.
            entry.addProperty("fields", NeuroSample.FIELDS);
            entry.addProperty("recorded", sample.recordedAt());
            entry.add("label", sample.label().toJson());
            entry.addProperty("data", sample.encode());
            array.add(entry);
        }
        root.add("samples", array);

        if (!ConfigIO.write(path, root)) {
            return false;
        }
        this.activeName = name;
        this.dirty = false;
        this.cachedNames = null;
        return true;
    }

    public boolean load(String name) {
        Path path = pathFor(name);
        if (path == null || !Files.exists(path)) {
            return false;
        }
        JsonObject root = ConfigIO.read(path);
        if (root == null) {
            return false;
        }

        List<NeuroSample> loaded = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("samples");
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                // Файл первой версии писался без "fields" — там кадр узкий, и decode
                // дополнит его сам. Подписи там тоже нет, и это честный UNKNOWN.
                int fields = entry.has("fields") ? integer(entry, "fields") : NeuroSample.LEGACY_FIELDS;
                NeuroSample sample = NeuroSample.decode(
                        string(entry, "data"),
                        integer(entry, "frames"),
                        fields,
                        longValue(entry, "recorded"),
                        entry.has("label") && entry.get("label").isJsonObject()
                                ? NeuroLabel.fromJson(entry.getAsJsonObject("label"))
                                : NeuroLabel.UNKNOWN
                );
                if (sample != null) {
                    loaded.add(sample);
                }
            }
        }

        this.samples.clear();
        this.samples.addAll(loaded);
        reindex();
        this.activeName = name;
        this.dirty = false;
        return true;
    }

    /** Pulls in the dataset the client last used, leaving an empty one when there is no file yet. */
    public void loadDefault() {
        if (!load(DEFAULT_NAME)) {
            this.activeName = DEFAULT_NAME;
        }
    }

    public void saveIfDirty() {
        if (this.dirty) {
            save(this.activeName);
        }
    }

    public boolean delete(String name) {
        Path path = pathFor(name);
        try {
            boolean deleted = path != null && Files.deleteIfExists(path);
            if (deleted) {
                this.cachedNames = null;
            }
            return deleted;
        } catch (IOException exception) {
            LOGGER.error("Failed to delete neuro dataset {}", path, exception);
            return false;
        }
    }

    /**
     * Dataset file names, cached because the rotation settings refresh their dropdown from
     * this every tick and the answer only changes on save or delete.
     */
    public List<String> names() {
        List<String> cached = this.cachedNames;
        if (cached != null) {
            return cached;
        }

        List<String> found = new ArrayList<>();
        Path directory = directory();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                files.map(file -> file.getFileName().toString())
                        .filter(fileName -> fileName.endsWith(EXTENSION))
                        .map(fileName -> fileName.substring(0, fileName.length() - EXTENSION.length()))
                        .sorted()
                        .forEach(found::add);
            } catch (IOException exception) {
                LOGGER.error("Failed to list neuro datasets in {}", directory, exception);
            }
        }
        if (!found.contains(DEFAULT_NAME)) {
            found.add(0, DEFAULT_NAME);
        }

        this.cachedNames = List.copyOf(found);
        return this.cachedNames;
    }

    public Path directory() {
        return ConfigIO.resolve(DIRECTORY);
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches(NAME_PATTERN);
    }

    private Path pathFor(String name) {
        return isValidName(name) ? directory().resolve(name + EXTENSION) : null;
    }

    private float value(int entry, int field) {
        NeuroSample sample = this.samples.get(entry >>> FRAME_BITS);
        return sample.get(entry & FRAME_MASK, field);
    }

    /**
     * Scans the cells within {@code radius} of the query and keeps the closest frames in
     * the two parallel arrays, returning how many slots are filled.
     */
    private int collect(NeuroQuery query, int radius, float[] bestDistance, int[] bestEntry, float duelBias) {
        int baseYaw = cellIndex(query.errYaw(), CELL_YAW);
        int basePitch = cellIndex(query.errPitch(), CELL_PITCH);
        int found = 0;

        for (int yawOffset = -radius; yawOffset <= radius; yawOffset++) {
            for (int pitchOffset = -radius; pitchOffset <= radius; pitchOffset++) {
                Cell cell = this.grid.get(key(baseYaw + yawOffset, basePitch + pitchOffset));
                if (cell == null) {
                    continue;
                }
                for (int index = 0; index < cell.size; index++) {
                    int entry = cell.entries[index];
                    NeuroSample sample = this.samples.get(entry >>> FRAME_BITS);
                    float distance = query.distanceSqr(
                            sample.data(), (entry & FRAME_MASK) * NeuroSample.FIELDS, duelBias);
                    found = insert(bestDistance, bestEntry, distance, entry, found);
                }
            }
        }
        return found;
    }

    private static int insert(float[] bestDistance, int[] bestEntry, float distance, int entry, int found) {
        int last = bestDistance.length - 1;
        if (distance >= bestDistance[last]) {
            return found;
        }

        int position = last;
        while (position > 0 && bestDistance[position - 1] > distance) {
            bestDistance[position] = bestDistance[position - 1];
            bestEntry[position] = bestEntry[position - 1];
            position--;
        }
        bestDistance[position] = distance;
        bestEntry[position] = entry;
        return Math.min(found + 1, bestDistance.length);
    }

    /** Draws one of the found neighbours in proportion to its weight. */
    private static int pick(float[] bestDistance, int found, float totalWeight) {
        float roll = ThreadLocalRandom.current().nextFloat() * totalWeight;
        for (int index = 0; index < found; index++) {
            roll -= 1.0F / (bestDistance[index] + WEIGHT_EPSILON);
            if (roll <= 0.0F) {
                return index;
            }
        }
        return found - 1;
    }

    private void indexSample(int sampleIndex, NeuroSample sample) {
        float[] data = sample.data();
        for (int frame = 0; frame < sample.frames(); frame++) {
            int offset = frame * NeuroSample.FIELDS;
            long key = key(
                    cellIndex(data[offset + NeuroSample.ERR_YAW], CELL_YAW),
                    cellIndex(data[offset + NeuroSample.ERR_PITCH], CELL_PITCH)
            );
            this.grid.computeIfAbsent(key, ignored -> new Cell()).add((sampleIndex << FRAME_BITS) | frame);
        }
    }

    private void reindex() {
        this.grid.clear();
        this.frameCount = 0;
        for (int index = 0; index < this.samples.size(); index++) {
            NeuroSample sample = this.samples.get(index);
            indexSample(index, sample);
            this.frameCount += sample.frames();
        }
    }

    private void trim() {
        if (this.samples.size() <= MAX_SAMPLES) {
            return;
        }
        int excess = Math.min(this.samples.size() - MAX_SAMPLES + TRIM_BATCH, this.samples.size());
        this.samples.subList(0, excess).clear();
        reindex();
    }

    private static int cellIndex(float value, float size) {
        return (int) Math.floor(value / size);
    }

    private static long key(int yawCell, int pitchCell) {
        return ((long) yawCell << 32) ^ (pitchCell & 0xFFFFFFFFL);
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static int integer(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
    }

    private static long longValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
    }

    /**
     * One grid bucket. Once full, {@link #add} keeps an unbiased random subset of
     * everything that ever landed here (reservoir sampling), so busy regions stay bounded
     * without over-representing whichever frames happened to arrive first.
     */
    private static final class Cell {
        private int[] entries = new int[16];
        private int size;
        private int seen;

        private void add(int entry) {
            this.seen++;
            if (this.size < CELL_CAPACITY) {
                if (this.size == this.entries.length) {
                    this.entries = Arrays.copyOf(this.entries, Math.min(this.entries.length * 2, CELL_CAPACITY));
                }
                this.entries[this.size++] = entry;
                return;
            }

            int slot = ThreadLocalRandom.current().nextInt(this.seen);
            if (slot < CELL_CAPACITY) {
                this.entries[slot] = entry;
            }
        }
    }
}
