package win.furryhook.feature.impl.misc;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.pve.PvpStateTracker;
import win.furryhook.utils.FriendManager;

import java.util.List;
import java.util.Locale;

public final class AutoTpaAcceptFeature extends Feature implements MinecraftContext {
    private static final List<String> REQUEST_MARKERS = List.of(
            "has requested teleport",
            "телепортироваться"
    );
    private static final long ACCEPT_COOLDOWN_MILLIS = 1000L;

    public final BooleanSetting friendsOnly = register(new BooleanSetting(
            "Friends Only", false
    ));
    public final BooleanSetting avoidCombat = register(new BooleanSetting(
            "Avoid Combat", true
    ));

    private long lastAcceptAt;

    public AutoTpaAcceptFeature() {
        super("AutoTpaAccept", "Automatically accepts teleport requests", FeatureCategory.MISC, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE || player() == null) {
            return;
        }
        if (!(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }

        String text = packet.content().getString();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (REQUEST_MARKERS.stream().noneMatch(normalized::contains)) {
            return;
        }
        if (this.avoidCombat.getValue() && isInCombat()) {
            return;
        }
        if (this.friendsOnly.getValue()
                && FriendManager.INSTANCE.getFriends().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .noneMatch(normalized::contains)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAcceptAt < ACCEPT_COOLDOWN_MILLIS) {
            return;
        }
        lastAcceptAt = now;
        player().connection.sendCommand("tpaccept");
    }

    private boolean isInCombat() {
        if (PvpStateTracker.INSTANCE.isActive() || player().hurtTime > 0) {
            return true;
        }
        AuraFeature aura = FeatureManager.INSTANCE.getFeature(AuraFeature.class);
        return aura != null && aura.getCurrentTarget() != null;
    }
}
