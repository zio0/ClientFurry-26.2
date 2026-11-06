package win.furryhook.pve;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.player.Player;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.AuraFeature;

import java.util.List;
import java.util.Locale;

public final class PvpStateTracker {
    public static final PvpStateTracker INSTANCE = new PvpStateTracker();

    private static final long LOCAL_COMBAT_MILLIS = 10_000L;
    private static final List<String> ENABLE_MARKERS = List.of(
            "pvp mode enabled",
            "combat mode enabled",
            "режим pvp активирован",
            "режим боя активирован",
            "до конца pvp",
            "до конца режима боя"
    );
    private static final List<String> DISABLE_MARKERS = List.of(
            "pvp mode disabled",
            "combat mode disabled",
            "режим pvp деактивирован",
            "режим боя деактивирован",
            "you are no longer in combat"
    );

    private volatile long activeUntil;
    private volatile boolean serverTagged;

    private PvpStateTracker() {
    }

    public boolean isActive() {
        return this.serverTagged || System.currentTimeMillis() < this.activeUntil;
    }

    public void markCombatFor(long millis) {
        this.activeUntil = Math.max(
                this.activeUntil,
                System.currentTimeMillis() + Math.max(0L, millis)
        );
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE
                || !(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }
        String text = packet.content().getString().toLowerCase(Locale.ROOT);
        if (DISABLE_MARKERS.stream().anyMatch(text::contains)) {
            this.serverTagged = false;
            this.activeUntil = 0L;
        } else if (ENABLE_MARKERS.stream().anyMatch(text::contains)) {
            this.serverTagged = true;
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null) {
            return;
        }
        if (player.hurtTime > 0 && player.getLastHurtByMob() instanceof Player) {
            markCombatFor(LOCAL_COMBAT_MILLIS);
        }
        AuraFeature aura = FeatureManager.INSTANCE.getEnabled(AuraFeature.class);
        if (aura != null && aura.getCurrentTarget() instanceof Player) {
            markCombatFor(LOCAL_COMBAT_MILLIS);
        }
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        reset();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        reset();
    }

    private void reset() {
        this.activeUntil = 0L;
        this.serverTagged = false;
    }
}
