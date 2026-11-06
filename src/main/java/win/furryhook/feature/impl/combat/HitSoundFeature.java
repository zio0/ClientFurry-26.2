package win.furryhook.feature.impl.combat;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.EntityAttackEvent;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ButtonSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ConfigIO;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Plays a chosen sound from config/furryhook/hitsounds on every attack - manual,
 * Aura, or a FakePlayer hit all trigger it via {@link EntityAttackEvent}.
 * Drop your own .wav/.ogg/.aiff/.au files into that folder and they're picked up
 * automatically - no in-game file picker, just an "Open Folder" shortcut.
 */
public final class HitSoundFeature extends Feature {
    private static final Logger LOGGER = LoggerFactory.getLogger(HitSoundFeature.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("wav", "wave", "aiff", "aif", "au", "ogg");
    private static final String NONE = "None";
    private static final int RESCAN_INTERVAL_TICKS = 100;

    /** Hard ceiling on how long one clip may hold a mixer line, however broken the file is. */
    private static final long MAX_PLAYBACK_MILLIS = 10_000L;

    /**
     * Playback now blocks its thread for the length of the clip, so an unbounded pool would turn a
     * fast combo into an unbounded number of threads and, worse, an unbounded number of open audio
     * lines — the exact resource this class was already leaking. Four voices is more overlap than
     * a hitsound needs, and the queue is deliberately shallow: when it is full the newest hit is
     * dropped. A missed tick of audio costs nothing; a mixer line that never comes back is what
     * put the client on the floor.
     */
    private static final ExecutorService PLAYBACK_EXECUTOR = new ThreadPoolExecutor(
            1,
            4,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "HitSound Playback");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    public final NumberSetting volume = register(new NumberSetting("Volume", 100.0, 0.0, 200.0, 1.0, "%"));
    public final ModeSetting sound = register(new ModeSetting("Sound", NONE, NONE));
    public final ButtonSetting openFolder = register(new ButtonSetting("Sounds Folder", "Open Folder", this::openSoundsFolder));

    private volatile Map<String, Path> loadedSounds = Map.of();
    private int tickCounter;

    public HitSoundFeature() {
        super("HitSound", "Plays your own sound files when you attack an entity", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    private Path soundsDir() {
        return ConfigIO.resolve("hitsounds");
    }

    @Override
    protected void onEnable() {
        rescan();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    @EventTarget
    public void onAttack(EntityAttackEvent event) {
        Path path = loadedSounds.get(sound.getValue());
        if (path != null) {
            playAsync(path);
        }
    }

    private void openSoundsFolder() {
        PLAYBACK_EXECUTOR.execute(() -> {
            Path dir = soundsDir();
            try {
                Files.createDirectories(dir);
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir.toFile());
                } else {
                    new ProcessBuilder("explorer.exe", dir.toString()).start();
                }
            } catch (IOException | UnsupportedOperationException exception) {
                LOGGER.warn("Failed to open hitsounds folder", exception);
                return;
            }
            rescan();
        });
    }

    private void rescan() {
        Path dir = soundsDir();
        Map<String, Path> found = new LinkedHashMap<>();

        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> entries = stream.sorted().toList();
                for (Path path : entries) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    if (SUPPORTED_EXTENSIONS.contains(extensionOf(path))) {
                        found.put(path.getFileName().toString(), path);
                    }
                }
            } catch (IOException exception) {
                LOGGER.warn("Failed to scan hitsounds folder", exception);
            }
        }

        loadedSounds = Map.copyOf(found);
        List<String> options = found.isEmpty() ? List.of(NONE) : List.copyOf(found.keySet());
        sound.updateModes(options);
    }

    private static String extensionOf(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void playAsync(Path path) {
        double percent = volume.getValue();
        if (percent <= 0) {
            return;
        }
        PLAYBACK_EXECUTOR.execute(() -> play(path, percent));
    }

    /**
     * Plays one clip and does not return until it has been closed again.
     *
     * <p>The previous version handed the close to a {@link LineEvent.Type#STOP} listener and
     * returned immediately. Every clip that never delivered that event stayed open forever, and an
     * open {@link Clip} is not a Java object — it is a native mixer line plus the whole decoded PCM
     * buffer, committed by the audio stack and never touched again. That is why the process could
     * sit at 52 GB of commit charge with a ten-gigabyte working set, and why it only ever died
     * after a fight rather than while standing in spawn.
     *
     * <p>Three separate ways that listener lost a clip:
     *
     * <ul>
     *   <li>It was attached after {@link Clip#open}, so a clip that finished early could fire and
     *       be gone before anything was listening.</li>
     *   <li>{@code close()} from inside the line's own callback runs on the dispatch thread while
     *       the line is still notifying, which Java Sound does not promise to honour.</li>
     *   <li>A clip that failed to start emitted no STOP at all, and nothing else ever freed it.</li>
     * </ul>
     *
     * <p>So the close moved to a {@code finally} on this thread, which already belongs to the
     * playback pool and is free to block. The latch is only an optimisation for the normal case;
     * the timeout is what guarantees the line comes back even when the event never arrives.
     */
    private void play(Path path, double percent) {
        Clip clip = null;
        try (AudioInputStream stream = openAudioStream(path)) {
            clip = AudioSystem.getClip();

            CountDownLatch finished = new CountDownLatch(1);
            clip.addLineListener(lineEvent -> {
                if (lineEvent.getType() == LineEvent.Type.STOP) {
                    finished.countDown();
                }
            });

            clip.open(stream);
            applyVolume(clip, percent);
            clip.start();

            // Wall clock the clip cannot outlive: its own length plus a second of slack for the
            // mixer. A malformed file reporting an unknown length falls back to the cap.
            long micros = clip.getMicrosecondLength();
            long waitMillis = micros > 0L
                    ? Math.min(MAX_PLAYBACK_MILLIS, micros / 1000L + 1_000L)
                    : MAX_PLAYBACK_MILLIS;
            finished.await(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException exception) {
            LOGGER.warn("Failed to play hitsound {}", path.getFileName(), exception);
        } finally {
            if (clip != null) {
                clip.stop();
                clip.close();
            }
        }
    }

    private AudioInputStream openAudioStream(Path path) throws IOException, UnsupportedAudioFileException {
        if ("ogg".equals(extensionOf(path))) {
            return decodeOgg(path);
        }
        return AudioSystem.getAudioInputStream(path.toFile());
    }

    private AudioInputStream decodeOgg(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer encoded = BufferUtils.createByteBuffer(bytes.length);
        encoded.put(bytes).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            long decoder = STBVorbis.stb_vorbis_open_memory(encoded, error, null);
            if (decoder == MemoryUtil.NULL) {
                throw new IOException("stb_vorbis_open_memory failed with error " + error.get(0));
            }
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(decoder, info);
                int channels = info.channels();
                int sampleRate = info.sample_rate();

                int lengthSamples = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
                ShortBuffer pcm = MemoryUtil.memAllocShort(lengthSamples * channels);
                try {
                    int decodedSamples = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
                    pcm.position(0).limit(decodedSamples * channels);

                    byte[] pcmBytes = new byte[pcm.remaining() * 2];
                    for (int i = 0; pcm.hasRemaining(); i++) {
                        short sample = pcm.get();
                        pcmBytes[i * 2] = (byte) (sample & 0xFF);
                        pcmBytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                    }

                    AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
                    return new AudioInputStream(new ByteArrayInputStream(pcmBytes), format, pcmBytes.length / format.getFrameSize());
                } finally {
                    MemoryUtil.memFree(pcm);
                }
            } finally {
                STBVorbis.stb_vorbis_close(decoder);
            }
        }
    }

    private void applyVolume(Clip clip, double percent) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float gain = (float) (20.0 * Math.log10(Math.max(0.0001, percent / 100.0)));
        gain = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), gain));
        gainControl.setValue(gain);
    }
}
