package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.economy.CommandCooldown;
import win.furryhook.pve.economy.EconomyCommands;
import win.furryhook.pve.economy.EconomyTextParser;
import win.furryhook.pve.economy.LocalPaymentLedger;
import win.furryhook.pve.economy.LocalSyncProtocol;
import win.furryhook.pve.economy.LocalSyncTokenStore;
import win.furryhook.pve.economy.LocalSyncTransport;
import win.furryhook.pve.economy.ScoreboardBalance;
import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.pve.server.ServerProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SynchronizationFeature extends PveFeature {
    public static final String MODE_OFF = "Off";
    public static final String MODE_REQUEST_ONLY = "Request Only";
    public static final String MODE_SEND_ONLY = "Send Only";
    public static final String MODE_REQUEST_AND_SEND = "Request & Send";

    private static final long HEARTBEAT_TICKS = 40L;
    private static final long PAYMENT_COOLDOWN_TICKS = 100L;
    private static final long REQUEST_COOLDOWN_TICKS = 200L;
    private static final long REQUEST_EXPIRY_TICKS = 300L;

    private static volatile SynchronizationFeature active;

    public final ModeSetting moneyMode = register(new ModeSetting(
            "Money Mode",
            MODE_REQUEST_AND_SEND,
            MODE_OFF,
            MODE_REQUEST_ONLY,
            MODE_SEND_ONLY,
            MODE_REQUEST_AND_SEND
    ));

    private final ConcurrentLinkedQueue<LocalSyncProtocol.MoneyRequest> received =
            new ConcurrentLinkedQueue<>();
    private final ArrayDeque<QueuedRequest> pending = new ArrayDeque<>();
    private final CommandCooldown paymentCooldown = new CommandCooldown();
    private final CommandCooldown requestCooldown = new CommandCooldown();

    private UUID instanceId;
    private LocalSyncTransport transport;
    private LocalPaymentLedger paymentLedger;
    private Path configDirectory;
    private long lastTick;
    private long nextHeartbeatTick;
    private boolean busyPaying;

    public SynchronizationFeature() {
        super(
                "Synchronization",
                "Coordinates authenticated money requests between local game instances",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE
        );
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        long tick = client.level.getGameTime();
        this.lastTick = tick;
        if (!isSupportedProfile(ServerAdapters.current().profile())
                || this.moneyMode.is(MODE_OFF)) {
            if (this.transport != null) {
                stopTransport();
                clearRuntime();
            }
            return;
        }
        if (!ensureTransport(client)) {
            return;
        }

        drainReceived(tick);
        if (!this.moneyMode.is(MODE_OFF) && tick >= this.nextHeartbeatTick) {
            publishStatus(player);
            this.nextHeartbeatTick = tick + HEARTBEAT_TICKS;
        }
        processPayment(client, player, tick);
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        stopTransport();
        clearRuntime();
    }

    @Override
    protected void onPveEnable() {
        clearRuntime();
        active = this;
        ensureTransport(Minecraft.getInstance());
    }

    @Override
    protected void onPveDisable() {
        if (active == this) {
            active = null;
        }
        stopTransport();
        clearRuntime();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        if (active == this) {
            active = null;
        }
        stopTransport();
        clearRuntime();
    }

    public static boolean requestMoney(int amount) {
        Minecraft client = Minecraft.getInstance();
        String recipient = client.player == null
                ? ""
                : client.player.getGameProfile().name();
        return requestMoney(recipient, amount, "");
    }

    public static boolean requestMoney(String recipient, int amount, String reason) {
        SynchronizationFeature feature = active;
        return feature != null && feature.sendMoneyRequest(recipient, amount);
    }

    public static boolean isActive() {
        SynchronizationFeature feature = active;
        return feature != null
                && feature.isEnabled()
                && !feature.moneyMode.is(MODE_OFF)
                && isSupportedProfile(ServerAdapters.current().profile());
    }

    public int peersOnline() {
        LocalSyncTransport current = this.transport;
        if (current == null || !current.isRunning()) {
            return 0;
        }
        return current.peers(ServerAdapters.current().profile().name()).size();
    }

    public boolean canRequest() {
        return this.moneyMode.is(MODE_REQUEST_ONLY)
                || this.moneyMode.is(MODE_REQUEST_AND_SEND);
    }

    public boolean canSend() {
        return this.moneyMode.is(MODE_SEND_ONLY)
                || this.moneyMode.is(MODE_REQUEST_AND_SEND);
    }

    public boolean isBusyPaying() {
        return this.busyPaying;
    }

    private boolean sendMoneyRequest(String recipient, int amount) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled()
                || !canRequest()
                || client.player == null
                || client.level == null
                || amount <= 0
                || !EconomyTextParser.isSafePlayerName(recipient)
                || !recipient.equalsIgnoreCase(client.player.getGameProfile().name())
                || !isSupportedProfile(ServerAdapters.current().profile())
                || !ensureTransport(client)) {
            return false;
        }
        long tick = client.level.getGameTime();
        if (!this.requestCooldown.tryAcquire(tick, REQUEST_COOLDOWN_TICKS)) {
            return false;
        }
        this.transport.request(new LocalSyncProtocol.MoneyRequest(
                this.instanceId,
                UUID.randomUUID(),
                ServerAdapters.current().profile().name(),
                recipient,
                amount
        ));
        return true;
    }

    private void processPayment(Minecraft client, LocalPlayer player, long tick) {
        this.busyPaying = false;
        if (!canSend() || !this.paymentCooldown.ready(tick)) {
            return;
        }
        while (!this.pending.isEmpty()
                && tick - this.pending.peekFirst().receivedTick() > REQUEST_EXPIRY_TICKS) {
            this.pending.removeFirst();
        }
        QueuedRequest queued = this.pending.peekFirst();
        if (queued == null) {
            return;
        }
        LocalSyncProtocol.MoneyRequest request = queued.request();
        String profile = ServerAdapters.current().profile().name();
        LocalSyncTransport.Peer requester = peer(request.sender(), profile);
        if (requester == null) {
            this.paymentCooldown.defer(tick, 5L);
            return;
        }
        if (!requester.status().canRequest()
                || !requester.status().playerName().equals(request.recipient())
                || !profile.equals(request.profile())
                || request.recipient().equalsIgnoreCase(player.getGameProfile().name())) {
            this.pending.removeFirst();
            return;
        }

        OptionalLong balance = ScoreboardBalance.read(player);
        if (balance.isEmpty() || balance.getAsLong() < request.amount()) {
            this.pending.removeFirst();
            return;
        }
        if (!claim(AutomationResource.CHAT)) {
            this.paymentCooldown.defer(tick, 5L);
            return;
        }
        try {
            if (!this.paymentLedger.claim(request.requestId(), LocalSyncProtocol.nowSeconds())) {
                this.pending.removeFirst();
                return;
            }
            EconomyCommands.pay(request.recipient(), request.amount()).ifPresent(command -> {
                ServerAdapter adapter = ServerAdapters.current();
                this.busyPaying = true;
                adapter.sendCommand(player, command);
                this.paymentCooldown.tryAcquire(tick, PAYMENT_COOLDOWN_TICKS);
            });
            this.pending.removeFirst();
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    private LocalSyncTransport.Peer peer(UUID sender, String profile) {
        if (this.transport == null) {
            return null;
        }
        for (LocalSyncTransport.Peer peer : this.transport.peers(profile)) {
            if (peer.status().sender().equals(sender)) {
                return peer;
            }
        }
        return null;
    }

    private void drainReceived(long tick) {
        LocalSyncProtocol.MoneyRequest request;
        while ((request = this.received.poll()) != null) {
            LocalSyncProtocol.MoneyRequest receivedRequest = request;
            if (this.pending.stream().noneMatch(existing ->
                    existing.request().requestId().equals(receivedRequest.requestId())
            )) {
                this.pending.addLast(new QueuedRequest(receivedRequest, tick));
            }
        }
    }

    private void publishStatus(LocalPlayer player) {
        this.transport.publish(new LocalSyncProtocol.Status(
                this.instanceId,
                ServerAdapters.current().profile().name(),
                player.getGameProfile().name(),
                canRequest(),
                canSend()
        ));
    }

    private boolean ensureTransport(Minecraft client) {
        if (this.transport != null && this.transport.isRunning()) {
            return true;
        }
        if (client == null
                || this.moneyMode.is(MODE_OFF)
                || !isSupportedProfile(ServerAdapters.current().profile())) {
            return false;
        }
        byte[] token = null;
        LocalSyncTransport candidate = null;
        try {
            this.configDirectory = win.furryhook.utils.ConfigIO.configDir();
            token = LocalSyncTokenStore.loadOrCreate(this.configDirectory);
            this.instanceId = UUID.randomUUID();
            this.paymentLedger = new LocalPaymentLedger(this.configDirectory);
            candidate = new LocalSyncTransport(
                    this.instanceId,
                    token,
                    this.received::add
            );
            candidate.start();
            this.transport = candidate;
            return true;
        } catch (IOException | RuntimeException ignored) {
            if (candidate != null) {
                candidate.close();
            }
            stopTransport();
            return false;
        } finally {
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private void stopTransport() {
        LocalSyncTransport current = this.transport;
        this.transport = null;
        if (current != null) {
            current.close();
        }
        this.instanceId = null;
        this.paymentLedger = null;
        this.received.clear();
        this.pending.clear();
    }

    private void clearRuntime() {
        this.paymentCooldown.reset();
        this.requestCooldown.reset();
        this.nextHeartbeatTick = 0L;
        this.busyPaying = false;
        this.received.clear();
        this.pending.clear();
        this.lastTick = 0L;
    }

    private static boolean isSupportedProfile(ServerProfile profile) {
        return profile == ServerProfile.FUNTIME || profile == ServerProfile.REALLYWORLD;
    }

    private record QueuedRequest(LocalSyncProtocol.MoneyRequest request, long receivedTick) {
    }
}
