package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.utils.FriendManager;

import java.util.List;
import java.util.Optional;

public final class AutoLeaveFeature extends PveFeature {
    public static final String ACTION_DISCONNECT = "Disconnect";
    public static final String ACTION_SERVER_COMMAND = "Server Command";
    public static final String CONDITION_LOW_HEALTH = "Low Health";
    public static final String CONDITION_MODERATOR = "Moderator";
    public static final String CONDITION_NEARBY_PLAYER = "Nearby Player";

    private static final long CLEAR_DEBOUNCE_MILLIS = 500L;

    public final ModeSetting action = register(new ModeSetting(
            "Action",
            ACTION_SERVER_COMMAND,
            ACTION_DISCONNECT,
            ACTION_SERVER_COMMAND
    ));
    public final TextSetting command = register(commandSetting());
    public final MultiSelectSetting conditions = register(new MultiSelectSetting(
            "Conditions",
            List.of(CONDITION_LOW_HEALTH, CONDITION_MODERATOR, CONDITION_NEARBY_PLAYER),
            CONDITION_LOW_HEALTH,
            CONDITION_MODERATOR,
            CONDITION_NEARBY_PLAYER
    ));
    public final NumberSetting minimumHealth = register(new NumberSetting(
            "Minimum Health",
            5.0,
            1.0,
            20.0,
            0.5,
            " HP"
    ).visibleWhen(() -> this.conditions.isSelected(CONDITION_LOW_HEALTH)));
    public final NumberSetting leaveDistance = register(new NumberSetting(
            "Trigger Distance",
            10.0,
            1.0,
            100.0,
            1.0,
            " blocks"
    ).visibleWhen(() -> this.conditions.isSelected(CONDITION_NEARBY_PLAYER)));
    public final NumberSetting cooldown = register(new NumberSetting(
            "Cooldown",
            5.0,
            1.0,
            30.0,
            1.0,
            " s"
    ));

    private boolean dangerLatched;
    private long clearStartedAt;
    private long nextActionAt;
    private ClientPacketListener connection;

    public AutoLeaveFeature() {
        super(
                "AutoLeave",
                "Leaves when health, nearby players, or moderator presence becomes unsafe",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.EMERGENCY
        );
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        syncConnection(client);
        LocalPlayer self = client.player;
        ClientLevel level = client.level;
        if (self == null
                || level == null
                || client.getConnection() == null
                || client.getCurrentServer() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (self.isCreative() || self.isSpectator()) {
            clearLatchAfterDebounce(now);
            return;
        }
        boolean danger = hasDanger(client, self, level);
        if (!danger) {
            clearLatchAfterDebounce(now);
            return;
        }

        this.clearStartedAt = 0L;
        if (this.dangerLatched || now < this.nextActionAt) {
            return;
        }
        if (!performAction(client, self)) {
            return;
        }

        this.dangerLatched = true;
        this.nextActionAt = now + Math.round(this.cooldown.getValue() * 1000.0);
    }

    @Override
    protected void onPveEnable() {
        resetRuntimeState();
    }

    @Override
    protected void onPveDisable() {
        resetRuntimeState();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        resetRuntimeState();
    }

    private boolean hasDanger(Minecraft client, LocalPlayer self, ClientLevel level) {
        if (this.conditions.isSelected(CONDITION_LOW_HEALTH)
                && self.getHealth() <= this.minimumHealth.getValue().floatValue()) {
            return true;
        }
        if (this.conditions.isSelected(CONDITION_MODERATOR)
                && ModeratorDetector.find(client, self).isPresent()) {
            return true;
        }
        return this.conditions.isSelected(CONDITION_NEARBY_PLAYER)
                && nearbyNonFriend(self, level, this.leaveDistance.getValue());
    }

    private boolean performAction(Minecraft client, LocalPlayer self) {
        if (this.action.is(ACTION_DISCONNECT)) {
            client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            return true;
        }

        Optional<String> safeCommand = SafeServerCommand.normalize(this.command.getValue());
        if (safeCommand.isEmpty() || !claim(AutomationResource.CHAT)) {
            return false;
        }
        try {
            self.connection.sendCommand(safeCommand.get());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    private void clearLatchAfterDebounce(long now) {
        if (!this.dangerLatched) {
            this.clearStartedAt = 0L;
            return;
        }
        if (this.clearStartedAt == 0L) {
            this.clearStartedAt = now;
            return;
        }
        if (now - this.clearStartedAt >= CLEAR_DEBOUNCE_MILLIS) {
            this.dangerLatched = false;
            this.clearStartedAt = 0L;
        }
    }

    private void resetRuntimeState() {
        this.connection = null;
        clearDebounceState();
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    private void syncConnection(Minecraft client) {
        ClientPacketListener current = client.getConnection();
        if (current == this.connection) {
            return;
        }
        this.connection = current;
        clearDebounceState();
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    private void clearDebounceState() {
        this.dangerLatched = false;
        this.clearStartedAt = 0L;
        this.nextActionAt = 0L;
    }

    static boolean nearbyNonFriend(LocalPlayer self, ClientLevel level, double distance) {
        double maxDistanceSquared = distance * distance;
        for (Player candidate : level.players()) {
            if (candidate == self
                    || candidate.getUUID().equals(self.getUUID())
                    || !candidate.isAlive()
                    || candidate.isCreative()
                    || candidate.isSpectator()
                    || FriendManager.INSTANCE.isFriend(candidate.getGameProfile().name())) {
                continue;
            }
            if (self.distanceToSqr(candidate) <= maxDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    private TextSetting commandSetting() {
        return new TextSetting("Command", "/hub", 64)
                .visibleWhen(() -> this.action.is(ACTION_SERVER_COMMAND));
    }
}
