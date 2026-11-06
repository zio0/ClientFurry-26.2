package win.furryhook.utils.combat.neuro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Один записанный замах: каждый тик, который игрок провёл, ведя цель, плюс подпись —
 * {@link NeuroLabel} — говорящая, чем этот замах кончился и в каких условиях снимался.
 * Кадр — это {@link #FIELDS} значений подряд.
 *
 * <p>Кадры лежат плоским массивом, а не объектами: набитый датасет держит сотни тысяч
 * кадров, и один массив на замах — это несколько мегабайт вместо сотен, а на диск замах
 * уходит одним base64-блобом вместо JSON-массива, который принтер разложил бы по строке
 * на число.
 *
 * <p>Каждый кадр — одна обучающая пара: {@link #ERR_YAW}..{@link #DUEL} описывают
 * ситуацию, в которую игрок смотрел, а {@link #DELTA_YAW}/{@link #DELTA_PITCH} — движение
 * мыши, которым он на неё ответил в следующий тик.
 */
public final class NeuroSample {
    /** Значений в кадре. */
    public static final int FIELDS = 16;

    /** Сколько полей было в первой версии формата — такие файлы дочитываются и дополняются. */
    public static final int LEGACY_FIELDS = 10;

    /** Знаковый разрыв по yaw между прицелом игрока и целью, градусы. */
    public static final int ERR_YAW = 0;
    /** Знаковый разрыв по pitch, градусы. */
    public static final int ERR_PITCH = 1;
    /** Насколько игрок довернул за прошлый тик — несёт инерцию ведения. */
    public static final int PREV_DELTA_YAW = 2;
    public static final int PREV_DELTA_PITCH = 3;
    /** С какой скоростью угол на цель уезжает сам, градусов за тик. */
    public static final int TARGET_YAW_SPEED = 4;
    public static final int TARGET_PITCH_SPEED = 5;
    /** Дистанция глаз-цель, блоков: близкие и дальние цели ведут по-разному. */
    public static final int DISTANCE = 6;
    /** Тиков с последнего удара — где замах стоит в кулдауне. */
    public static final int SINCE_HIT = 7;
    /** Движение мыши за следующий тик — то, что модель и предсказывает. */
    public static final int DELTA_YAW = 8;
    public static final int DELTA_PITCH = 9;

    /** Собственная скорость цели, блоков за тик: убегающего ведут не так, как топчущегося. */
    public static final int TARGET_SPEED = 10;
    /** Сколько отдельных движений мыши пришлось на этот тик. */
    public static final int MOUSE_STEPS = 11;
    /** Самый крупный одиночный подтиковый шаг — подпись рывка. */
    public static final int MOUSE_PEAK = 12;
    /** Ровность подтиковой серии, 0..1. Единица — рука шла ровно. */
    public static final int SMOOTHNESS = 13;
    /** Модуль изменения дельты между тиками: сколько рука дёрнулась относительно себя. */
    public static final int JERK = 14;
    /** 1, если кадр снят один на один; 0 — в свалке. */
    public static final int DUEL = 15;

    /** Номера кадров пакуются индексом {@link NeuroDataset} в 8 бит. */
    public static final int MAX_FRAMES = 255;

    private final float[] data;
    private final int frames;
    private final long recordedAt;
    private final NeuroLabel label;

    public NeuroSample(float[] data, int frames, long recordedAt, NeuroLabel label) {
        if (frames < 1 || frames > MAX_FRAMES) {
            throw new IllegalArgumentException("Frame count out of range: " + frames);
        }
        if (data.length < frames * FIELDS) {
            throw new IllegalArgumentException("Sample holds " + data.length + " values, needs " + frames * FIELDS);
        }
        this.data = data;
        this.frames = frames;
        this.recordedAt = recordedAt;
        this.label = label == null ? NeuroLabel.UNKNOWN : label;
    }

    public int frames() {
        return this.frames;
    }

    public long recordedAt() {
        return this.recordedAt;
    }

    public NeuroLabel label() {
        return this.label;
    }

    public float get(int frame, int field) {
        return this.data[frame * FIELDS + field];
    }

    /** Массив-подложка; только на чтение — он отдаётся наружу, чтобы скан kNN не аллоцировал. */
    float[] data() {
        return this.data;
    }

    public String encode() {
        ByteBuffer buffer = ByteBuffer.allocate(this.frames * FIELDS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(this.data, 0, this.frames * FIELDS);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Разбор замаха с диска. {@code storedFields} — ширина кадра в файле: старые датасеты
     * писались по {@link #LEGACY_FIELDS}, и такие кадры дочитываются в нынешнюю ширину с
     * нулями в новых полях. Ноль там означает ровно то, что и означал: этого не измеряли.
     *
     * <p>Возвращает null на любой битой записи — испорченный замах выбрасывается, а не
     * летит исключением через весь датасет.
     */
    public static NeuroSample decode(String encoded, int frames, int storedFields, long recordedAt, NeuroLabel label) {
        if (encoded == null || frames < 1 || frames > MAX_FRAMES) {
            return null;
        }
        int width = storedFields <= 0 ? LEGACY_FIELDS : storedFields;
        if (width > FIELDS) {
            // Файл из более новой сборки: лишние поля отбрасываются, известные читаются.
            width = FIELDS;
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            return null;
        }

        int storedWidth = storedFields <= 0 ? LEGACY_FIELDS : storedFields;
        int storedValues = frames * storedWidth;
        if (bytes.length < storedValues * Float.BYTES) {
            return null;
        }

        float[] stored = new float[storedValues];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(stored);
        for (float value : stored) {
            if (!Float.isFinite(value)) {
                return null;
            }
        }

        if (storedWidth == FIELDS) {
            return new NeuroSample(stored, frames, recordedAt, label);
        }

        float[] data = new float[frames * FIELDS];
        for (int frame = 0; frame < frames; frame++) {
            System.arraycopy(stored, frame * storedWidth, data, frame * FIELDS, width);
        }
        return new NeuroSample(data, frames, recordedAt, label);
    }
}
