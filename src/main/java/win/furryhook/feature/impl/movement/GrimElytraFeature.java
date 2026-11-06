package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class GrimElytraFeature extends Feature {
    public final BooleanSetting velocityStart = register(new BooleanSetting("Velocity Start", true));
    public final NumberSetting airborneTicks = register(new NumberSetting("Airborne Ticks", 3, 0, 12, 1, " ticks"));
    private int airTicks;
    private boolean velocityReceived;
    private boolean started;

    public GrimElytraFeature() {
        super("GrimElytra", "Retries fall-flying after airborne transitions", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE
                || !(event.getPacket() instanceof ClientboundSetEntityMotionPacket packet)) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && packet.id() == player.getId()) {
            velocityReceived = true;
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null || player.isFallFlying() || player.onGround()) {
            reset();
            return;
        }

        airTicks++;
        if (!started && ((velocityStart.getValue() && velocityReceived)
                || airTicks >= airborneTicks.getValue().intValue())) {
            started = true;
        }

        if (started) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
        }
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        airTicks = 0;
        velocityReceived = false;
        started = false;
    }
}
