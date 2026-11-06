package win.furryhook.feature.impl.pve.autowarden;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramNotifier implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5L);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8L);
    private static final String TOKEN_ENV = "furryhook_TELEGRAM_BOT_TOKEN";
    private static final String CHAT_ENV = "furryhook_TELEGRAM_CHAT_ID";

    private final Path secretFile;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final AtomicReference<LinkStatus> status = new AtomicReference<>(LinkStatus.UNCONFIGURED);

    private volatile Credentials credentials;
    private volatile boolean closed;

    public TelegramNotifier(Path gameDirectory) {
        this.secretFile = gameDirectory
                .resolve("furryhook-secrets")
                .resolve("autowarden-telegram.properties");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "furryhook-autowarden-telegram");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(this.executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Path secretFile() {
        return this.secretFile;
    }

    public LinkStatus status() {
        return this.status.get();
    }

    public boolean isLinked() {
        return status() == LinkStatus.LINKED;
    }

    public String configurationHint() {
        return "Set " + TOKEN_ENV + " and " + CHAT_ENV
                + ", or fill " + this.secretFile.toAbsolutePath();
    }

    public CompletableFuture<LinkStatus> reloadAndVerify() {
        if (this.closed) {
            return CompletableFuture.completedFuture(LinkStatus.CLOSED);
        }
        this.status.set(LinkStatus.CHECKING);
        return CompletableFuture.supplyAsync(this::loadCredentials, this.executor)
                .thenCompose(loaded -> {
                    if (loaded == null) {
                        this.status.set(LinkStatus.UNCONFIGURED);
                        return CompletableFuture.completedFuture(LinkStatus.UNCONFIGURED);
                    }
                    replaceCredentials(loaded);
                    HttpRequest request = request("getMe", "");
                    return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .orTimeout(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                            .handle((response, error) -> {
                                LinkStatus next = error == null
                                        && response.statusCode() >= 200
                                        && response.statusCode() < 300
                                        ? LinkStatus.LINKED
                                        : LinkStatus.ERROR;
                                this.status.set(next);
                                return next;
                            });
                })
                .exceptionally(error -> {
                    this.status.set(LinkStatus.ERROR);
                    return LinkStatus.ERROR;
                });
    }

    public CompletableFuture<Boolean> send(String message) {
        if (this.closed || message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        Credentials current = this.credentials;
        if (current == null || !isLinked()) {
            return CompletableFuture.completedFuture(false);
        }
        String body = "chat_id=" + encode(current.chatId())
                + "&disable_web_page_preview=true"
                + "&text=" + encode(message);
        HttpRequest request = request("sendMessage", body);
        return this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> error == null
                        && response.statusCode() >= 200
                        && response.statusCode() < 300);
    }

    @Override
    public void close() {
        this.closed = true;
        Credentials current = this.credentials;
        this.credentials = null;
        if (current != null) {
            current.clear();
        }
        this.status.set(LinkStatus.CLOSED);
        this.executor.shutdownNow();
    }

    private Credentials loadCredentials() {
        String token = trimToNull(System.getenv(TOKEN_ENV));
        String chat = trimToNull(System.getenv(CHAT_ENV));
        if (token == null) {
            token = trimToNull(System.getProperty("furryhook.telegram.botToken"));
        }
        if (chat == null) {
            chat = trimToNull(System.getProperty("furryhook.telegram.chatId"));
        }
        if (token == null || chat == null) {
            ensureSecretTemplate();
            Properties properties = new Properties();
            if (Files.isRegularFile(this.secretFile)) {
                try (var input = Files.newInputStream(this.secretFile)) {
                    properties.load(input);
                    token = trimToNull(properties.getProperty("botToken"));
                    chat = trimToNull(properties.getProperty("chatId"));
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        if (token == null || chat == null || !validToken(token) || !validChat(chat)) {
            return null;
        }
        return new Credentials(token.toCharArray(), chat);
    }

    private void ensureSecretTemplate() {
        if (Files.exists(this.secretFile)) {
            return;
        }
        try {
            Files.createDirectories(this.secretFile.getParent());
            Files.writeString(
                    this.secretFile,
                    "# Auto Warden Telegram secrets. Keep this file private.\n"
                            + "botToken=\n"
                            + "chatId=\n",
                    StandardCharsets.UTF_8
            );
            setOwnerOnlyPermissions(this.secretFile);
        } catch (IOException | UnsupportedOperationException ignored) {

        }
    }

    private HttpRequest request(String method, String body) {
        Credentials current = this.credentials;
        if (current == null) {
            throw new IllegalStateException("Telegram credentials are not loaded");
        }
        String token = new String(current.token());
        URI uri = URI.create("https://api.telegram.org/bot" + token + "/" + method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        return body.isEmpty()
                ? builder.GET().build()
                : builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
    }

    private void replaceCredentials(Credentials next) {
        Credentials previous = this.credentials;
        this.credentials = next;
        if (previous != null) {
            previous.clear();
        }
    }

    private static void setOwnerOnlyPermissions(Path file) throws IOException {
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        );
        Files.setPosixFilePermissions(file, permissions);
    }

    private static boolean validToken(String token) {
        return token.length() >= 20
                && token.length() <= 256
                && token.matches("[0-9]{5,}:[A-Za-z0-9_-]{20,}");
    }

    private static boolean validChat(String chat) {
        return chat.length() <= 32 && chat.matches("-?[0-9]{1,24}");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public enum LinkStatus {
        UNCONFIGURED,
        CHECKING,
        LINKED,
        ERROR,
        CLOSED
    }

    private record Credentials(char[] token, String chatId) {
        private Credentials {
            token = Arrays.copyOf(token, token.length);
        }

        private void clear() {
            Arrays.fill(this.token, '\0');
        }
    }
}
