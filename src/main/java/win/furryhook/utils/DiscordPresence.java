package win.furryhook.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.ClientStartEvent;
import win.furryhook.event.events.lifecycle.ShutdownEvent;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discord Rich Presence for furryhook.
 *
 * <p>Talks the Discord IPC protocol directly over the local named pipe (Windows) or unix
 * domain socket (Linux/macOS), so it needs no native library or extra dependency.
 *
 * <p>Presence shown: first line {@code UID » <discord user id>} (or {@code UID » null} until the
 * IPC READY frame arrives), second line the static {@code Build » Alpha} tag.
 */
public final class DiscordPresence {
    public static final DiscordPresence INSTANCE = new DiscordPresence();

    /** Discord application (client) id backing this presence. */
    private static final String CLIENT_ID = "1543358183077322802";

    /** Second line of the presence: static build tag. */
    private static final String BUILD_TAG = "Build » Alpha";

    /**
     * Asset key of the small corner image. This is NOT a file path or URL: the image must be
     * uploaded in the Discord Developer Portal (app {@value #CLIENT_ID} &rarr; Rich Presence &rarr;
     * Art Assets) under exactly this key. Art assets are static, so upload a PNG frame of DSF.gif.
     */
    private static final String SMALL_IMAGE_KEY = "dsf";

    /** Tooltip shown when hovering the small image. */
    private static final String SMALL_IMAGE_TEXT = "furryhook";

    /** Only refresh the pushed activity roughly twice a second (client tick == 20 Hz). */
    private static final int UPDATE_INTERVAL_TICKS = 40;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "furryhook-discord-rpc");
                thread.setDaemon(true);
                return thread;
            });

    /** Latest presence computed on the game thread, consumed by the worker thread. */
    private final AtomicReference<Presence> pending = new AtomicReference<>();

    private Transport transport;
    private boolean connected;
    private volatile boolean started;
    private volatile boolean stopped;

    private Presence lastSent;
    private long lastReconnectAttempt;
    private int tickCounter;

    /** Username of the Discord account currently running the client, learned from the READY frame. */
    private volatile String discordUsername;

    /** The connected Discord account's username, or null until the IPC READY frame arrives. */
    public String username() {
        return this.discordUsername;
    }

    /** User id of the Discord account currently running the client, learned from the READY frame. */
    private volatile String discordUserId;

    private DiscordPresence() {
    }

    @EventTarget
    public void onClientStart(ClientStartEvent event) {
        if (started || stopped) {
            return;
        }
        started = true;
        pending.set(computePresence(null));
        worker.scheduleWithFixedDelay(this::pump, 0L, 3L, TimeUnit.SECONDS);
    }

    @EventTarget
    public void onGameTick(GameTickEvent event) {
        if (stopped) {
            return;
        }
        if (++tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        pending.set(computePresence(event.getClient()));
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        if (!stopped) {
            tickCounter = 0;
            pending.set(computePresence(event.getClient()));
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        if (!stopped) {
            tickCounter = 0;
            pending.set(computePresence(null));
        }
    }

    @EventTarget
    public void onShutdown(ShutdownEvent event) {
        shutdown();
    }

    /** Builds the desired presence: first line the connected Discord user id, second the build tag. */
    private Presence computePresence(Minecraft client) {
        return new Presence("UID » 0", BUILD_TAG);
    }

    /** Worker-thread loop: keep the pipe connected and push the latest presence when it changes. */
    private void pump() {
        if (stopped) {
            return;
        }
        try {
            if (!connected) {
                long now = System.currentTimeMillis();
                if (now - lastReconnectAttempt < 15_000L) {
                    return;
                }
                lastReconnectAttempt = now;
                connect();
            }
            if (!connected) {
                // Discord isn't reachable yet (no pipe found): try again on the next tick.
                return;
            }

            Presence presence = pending.get();
            if (presence != null && !presence.equals(lastSent)) {
                sendActivity(presence);
                lastSent = presence;
            }
        } catch (IOException e) {
            // Discord not running / pipe closed: drop the connection and retry later.
            closeQuietly();
        } catch (RuntimeException e) {
            // Never let an unexpected error kill the scheduled task permanently.
            closeQuietly();
        }
    }

    private void connect() throws IOException {
        transport = Transport.open();
        if (transport == null) {
            return;
        }

        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", CLIENT_ID);
        transport.send(Transport.OP_HANDSHAKE, handshake.toString());
        JsonObject readyUser = parseUser(transport.readFrame()); // READY carries the logged-in user
        discordUsername = readyUser == null || readyUser.get("username") == null
                || readyUser.get("username").isJsonNull() ? null : readyUser.get("username").getAsString();
        discordUserId = readyUser == null || readyUser.get("id") == null
                || readyUser.get("id").isJsonNull() ? null : readyUser.get("id").getAsString();

        connected = true;
        lastSent = null; // force a fresh activity push after (re)connecting
    }

    private void sendActivity(Presence presence) throws IOException {
        JsonObject activity = new JsonObject();
        activity.addProperty("details", presence.details);
        if (presence.state != null) {
            activity.addProperty("state", presence.state);
        }

        JsonObject assets = new JsonObject();
        assets.addProperty("small_image", SMALL_IMAGE_KEY);
        assets.addProperty("small_text", SMALL_IMAGE_TEXT);
        activity.add("assets", assets);

        JsonObject args = new JsonObject();
        args.addProperty("pid", (int) ProcessHandle.current().pid());
        args.add("activity", activity);

        JsonObject frame = new JsonObject();
        frame.addProperty("cmd", "SET_ACTIVITY");
        frame.add("args", args);
        frame.addProperty("nonce", UUID.randomUUID().toString());

        transport.send(Transport.OP_FRAME, frame.toString());
        transport.readFrame(); // consume the command response, keeping the stream balanced
    }

    /** Extracts {@code data.user} from a READY frame payload, or null if absent. */
    private static JsonObject parseUser(byte[] payload) {
        try {
            JsonElement root = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            JsonElement user = root.getAsJsonObject().getAsJsonObject("data").get("user");
            return user == null || user.isJsonNull() ? null : user.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        worker.execute(this::closeQuietly);
        worker.shutdown();
    }

    private void closeQuietly() {
        connected = false;
        lastSent = null;
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    /** Immutable snapshot of what should be shown; {@code state} is null for the idle line. */
    private record Presence(String details, String state) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Presence other
                    && Objects.equals(details, other.details)
                    && Objects.equals(state, other.state);
        }
    }

    /**
     * Minimal Discord IPC transport. Frames are {@code [op:int32-le][len:int32-le][json]}. All
     * calls happen on the single worker thread, so request/response stays strictly sequential.
     */
    private static final class Transport {
        static final int OP_HANDSHAKE = 0;
        static final int OP_FRAME = 1;

        private final InputStream in;
        private final OutputStream out;
        private final AutoCloseable owner;

        private Transport(InputStream in, OutputStream out, AutoCloseable owner) {
            this.in = in;
            this.out = out;
            this.owner = owner;
        }

        static Transport open() {
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("win");
            for (int i = 0; i < 10; i++) {
                try {
                    return windows ? openPipe(i) : openSocket(i);
                } catch (IOException ignored) {
                    // Try the next ipc slot (discord-ipc-0 .. discord-ipc-9).
                }
            }
            return null;
        }

        private static Transport openPipe(int slot) throws IOException {
            RandomAccessFile pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + slot, "rw");
            InputStream in = new InputStream() {
                @Override
                public int read() throws IOException {
                    return pipe.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return pipe.read(b, off, len);
                }
            };
            OutputStream out = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    pipe.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    pipe.write(b, off, len);
                }
            };
            return new Transport(in, out, pipe);
        }

        private static Transport openSocket(int slot) throws IOException {
            Path socketPath = Path.of(ipcDir(), "discord-ipc-" + slot);
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return new Transport(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel);
        }

        private static String ipcDir() {
            for (String key : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "/tmp";
        }

        void send(int opcode, String payload) throws IOException {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(opcode);
            buffer.putInt(data.length);
            buffer.put(data);
            out.write(buffer.array());
            out.flush();
        }

        /** Reads one inbound frame and returns its JSON payload; throws on EOF/close so the caller can reconnect. */
        byte[] readFrame() throws IOException {
            byte[] header = readFully(8);
            int length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (length < 0 || length > (1 << 20)) {
                throw new IOException("Unexpected Discord frame length: " + length);
            }
            return readFully(length);
        }

        private byte[] readFully(int length) throws IOException {
            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int count = in.read(data, read, length - read);
                if (count < 0) {
                    throw new IOException("Discord pipe closed");
                }
                read += count;
            }
            return data;
        }

        void close() {
            try {
                owner.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
