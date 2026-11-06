package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AutoAuthFeature extends PveFeature {
    public static final String MODE_GENERATED = "Generated";
    public static final String MODE_CUSTOM = "Custom Password";

    private static final long RESOURCE_RETRY_MILLIS = 250L;
    private static final long CREDENTIAL_RETRY_MILLIS = 5000L;
    private static final Pattern SAFE_PASSWORD =
            Pattern.compile("[A-Za-z0-9!@#$%^&*()_+\\-=.,:?~]{4,64}");

    public final ModeSetting passwordMode = register(new ModeSetting(
            "Mode",
            MODE_GENERATED,
            MODE_GENERATED,
            MODE_CUSTOM
    ));
    public final TextSetting customPassword = register(customPasswordSetting());
    public final NumberSetting cooldown = register(new NumberSetting(
            "Cooldown",
            3.0,
            1.0,
            15.0,
            0.5,
            " s"
    ));

    private final AutoAuthCredentialStore credentialStore;
    private final ArrayDeque<AutoAuthPromptParser.Prompt> pendingPrompts = new ArrayDeque<>();
    private final EnumSet<AutoAuthPromptParser.Prompt> handledPrompts =
            EnumSet.noneOf(AutoAuthPromptParser.Prompt.class);

    private ClientPacketListener connection;
    private long nextActionAt;

    public AutoAuthFeature() {
        this(new AutoAuthCredentialStore());
    }

    AutoAuthFeature(AutoAuthCredentialStore credentialStore) {
        super(
                "AutoAuth",
                "Automatically responds to server login and registration prompts",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE
        );
        this.credentialStore = credentialStore;
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE) {
            return;
        }
        String text = incomingText(event.getPacket());
        Optional<AutoAuthPromptParser.Prompt> prompt = AutoAuthPromptParser.parse(text);
        if (prompt.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (isEnabled()) {
                enqueue(client, prompt.get());
            }
        });
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        syncConnection(client);
        if (client.player == null
                || client.level == null
                || client.getCurrentServer() == null
                || this.connection == null) {
            return;
        }

        AutoAuthPromptParser.Prompt prompt = nextPrompt();
        if (prompt == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.nextActionAt) {
            return;
        }

        AutoAuthCredentialStore.Scope scope = scope(client);
        Optional<String> password;
        try {
            password = resolvePassword(scope);
        } catch (IOException | GeneralSecurityException exception) {
            this.nextActionAt = now + CREDENTIAL_RETRY_MILLIS;
            return;
        }
        if (password.isEmpty()) {
            this.nextActionAt = now + CREDENTIAL_RETRY_MILLIS;
            return;
        }

        if (!claim(AutomationResource.CHAT)) {
            this.nextActionAt = now + RESOURCE_RETRY_MILLIS;
            return;
        }

        String command = command(prompt, password.get());
        boolean sent = false;
        try {
            client.player.connection.sendCommand(command);
            sent = true;
        } catch (RuntimeException ignored) {
            this.nextActionAt = now + RESOURCE_RETRY_MILLIS;
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }

        if (sent) {
            this.pendingPrompts.removeFirstOccurrence(prompt);
            this.handledPrompts.add(prompt);
            this.nextActionAt = now + Math.round(this.cooldown.getValue() * 1000.0);
        }
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        resetRuntimeState();
    }

    @Override
    protected void onPveEnable() {
        resetRuntimeState();
    }

    @Override
    protected void onPveDisable() {
        resetRuntimeState();
        this.customPassword.setValue("");
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        resetRuntimeState();
        this.customPassword.setValue("");
    }

    static boolean isValidPassword(String password) {
        return password != null && SAFE_PASSWORD.matcher(password).matches();
    }

    static String incomingText(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket systemChat) {
            return systemChat.content().getString();
        }
        if (packet instanceof ClientboundDisguisedChatPacket disguisedChat) {
            return disguisedChat.message().getString();
        }
        if (packet instanceof ClientboundPlayerChatPacket playerChat) {
            Component unsigned = playerChat.unsignedContent();
            return unsigned != null ? unsigned.getString() : playerChat.body().content();
        }
        return null;
    }

    private void enqueue(Minecraft client, AutoAuthPromptParser.Prompt prompt) {
        syncConnection(client);
        if (this.connection == null
                || this.handledPrompts.contains(prompt)
                || this.pendingPrompts.contains(prompt)) {
            return;
        }
        this.pendingPrompts.addLast(prompt);
    }

    private AutoAuthPromptParser.Prompt nextPrompt() {
        while (!this.pendingPrompts.isEmpty()
                && this.handledPrompts.contains(this.pendingPrompts.peekFirst())) {
            this.pendingPrompts.removeFirst();
        }
        return this.pendingPrompts.peekFirst();
    }

    private Optional<String> resolvePassword(AutoAuthCredentialStore.Scope scope)
            throws IOException, GeneralSecurityException {
        if (this.passwordMode.is(MODE_GENERATED)) {
            return Optional.of(this.credentialStore.generatedPassword(scope));
        }

        String entered = this.customPassword.getValue();
        if (!entered.isEmpty()) {
            if (!isValidPassword(entered)) {
                return Optional.empty();
            }
            this.credentialStore.saveCustomPassword(scope, entered);
            this.customPassword.setValue("");
            return Optional.of(entered);
        }
        return this.credentialStore.loadCustomPassword(scope)
                .filter(AutoAuthFeature::isValidPassword);
    }

    private void syncConnection(Minecraft client) {
        ClientPacketListener current = client.getConnection();
        if (current == this.connection) {
            return;
        }
        this.connection = current;
        this.pendingPrompts.clear();
        this.handledPrompts.clear();
        this.nextActionAt = 0L;
    }

    private void resetRuntimeState() {
        this.connection = null;
        this.pendingPrompts.clear();
        this.handledPrompts.clear();
        this.nextActionAt = 0L;
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    private static AutoAuthCredentialStore.Scope scope(Minecraft client) {
        ServerData server = client.getCurrentServer();
        String account = client.player != null
                ? client.player.getGameProfile().name()
                : client.getUser().getName();
        return new AutoAuthCredentialStore.Scope(server.ip, account);
    }

    private static String command(AutoAuthPromptParser.Prompt prompt, String password) {
        return switch (prompt) {
            case LOGIN -> "login " + password;
            case REGISTER -> "register " + password + ' ' + password;
        };
    }

    private TextSetting customPasswordSetting() {
        TextSetting setting = new TextSetting("Password", "", 64).secret();
        setting.nonPersistent();
        setting.visibleWhen(() -> this.passwordMode.is(MODE_CUSTOM));
        return setting;
    }
}
