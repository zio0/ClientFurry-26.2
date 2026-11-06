package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PveStateMachine;
import win.furryhook.pve.economy.CommandCooldown;
import win.furryhook.pve.economy.EconomyChat;
import win.furryhook.pve.economy.EconomyCommands;
import win.furryhook.pve.economy.EconomyTextParser;
import win.furryhook.pve.economy.ScoreboardBalance;
import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.pve.server.ServerProfile;

import java.util.OptionalLong;

public final class ClanInvestFeature extends PveFeature {
    private static final long CONFIRMATION_TIMEOUT_TICKS = 80L;
    private static final long COMMAND_COOLDOWN_TICKS = 100L;

    public final TextSetting currencyThreshold = register(new TextSetting(
            "Currency Threshold",
            "1000000"
    ));
    public final NumberSetting investPercentage = register(new NumberSetting(
            "Invest Percentage",
            30.0,
            1.0,
            100.0,
            1.0,
            "%"
    ));

    private final PveStateMachine<State> machine = new PveStateMachine<>(State.ARMED);
    private final CommandCooldown commandCooldown = new CommandCooldown();

    private long lastTick;
    private long pendingAmount;

    public ClanInvestFeature() {
        super(
                "ClanInvest",
                "Invests a percentage of the displayed balance after a threshold",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE
        );
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null) {
            return;
        }
        long tick = client.level.getGameTime();
        this.lastTick = tick;
        if (ServerAdapters.current().profile() != ServerProfile.FUNTIME) {
            return;
        }

        if (this.machine.is(State.WAITING_CONFIRMATION)
                && this.machine.ticksInState(tick) >= CONFIRMATION_TIMEOUT_TICKS) {
            this.pendingAmount = 0L;
            this.machine.transition(State.ARMED, tick);
        }

        int threshold = positiveThreshold();
        if (threshold <= 0) {
            return;
        }
        OptionalLong balanceResult = ScoreboardBalance.read(client.player);
        if (balanceResult.isEmpty()) {
            return;
        }
        long balance = balanceResult.getAsLong();
        if (balance < threshold) {
            this.pendingAmount = 0L;
            this.machine.transition(State.ARMED, tick);
            return;
        }
        if (!this.machine.is(State.ARMED) || !this.commandCooldown.ready(tick)) {
            return;
        }

        long boundedBalance = Math.min(Integer.MAX_VALUE, balance);
        long amount = Math.max(
                1L,
                Math.min(
                        Integer.MAX_VALUE,
                        boundedBalance * Math.round(this.investPercentage.getValue()) / 100L
                )
        );
        EconomyCommands.clanInvest(amount).ifPresent(command -> {
            if (!claim(AutomationResource.CHAT)) {
                this.commandCooldown.defer(tick, 5L);
                return;
            }
            try {
                ServerAdapter adapter = ServerAdapters.current();
                adapter.sendCommand(client.player, command);
                this.pendingAmount = amount;
                this.machine.transition(State.WAITING_CONFIRMATION, tick);
                this.commandCooldown.tryAcquire(tick, COMMAND_COOLDOWN_TICKS);
            } finally {
                PveAutomationCoordinator.INSTANCE.release(this);
            }
        });
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE) {
            return;
        }
        String text = EconomyChat.incomingText(event.getPacket());
        if (text == null) {
            return;
        }
        String normalized = EconomyTextParser.normalize(text);
        Minecraft.getInstance().execute(() -> handleChat(normalized));
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        resetRuntime();
    }

    @Override
    protected void onPveEnable() {
        resetRuntime();
    }

    @Override
    protected void onPveDisable() {
        resetRuntime();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        resetRuntime();
    }

    private void handleChat(String text) {
        if (!isEnabled()) {
            return;
        }
        if (EconomyTextParser.containsAny(
                text,
                "/clan create - создать клан",
                "you are not in a clan"
        ) || EconomyTextParser.containsAny(
                text,
                "вы не можете пополнить баланс клана",
                "cannot deposit to the clan"
        )) {
            setEnabled(false);
            return;
        }
        if (this.machine.is(State.WAITING_CONFIRMATION)
                && EconomyTextParser.containsAny(
                text,
                "пополнил баланс казны",
                "clan treasury",
                "clan balance"
        )) {
            this.pendingAmount = 0L;
            this.machine.transition(State.LATCHED, this.lastTick);
        }
    }

    private int positiveThreshold() {
        String value = this.currencyThreshold.getValue();
        if (value == null || !value.matches("\\d+")) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void resetRuntime() {
        this.machine.reset(0L);
        this.commandCooldown.reset();
        this.pendingAmount = 0L;
        this.lastTick = 0L;
    }

    enum State {
        ARMED,
        WAITING_CONFIRMATION,
        LATCHED
    }
}
