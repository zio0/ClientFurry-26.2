package win.furryhook.feature.impl.combat;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;

public final class VelocityFeature extends Feature implements MinecraftContext {
    public VelocityFeature() {
        super("Velocity", "Removes knockback", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE || player() == null) {
            return;
        }
        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket packet && packet.id() == player().getId()) {
            event.cancel();
        }
    }
}
