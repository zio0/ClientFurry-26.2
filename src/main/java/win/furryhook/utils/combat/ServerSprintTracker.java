package win.furryhook.utils.combat;

import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketSendEvent;

public final class ServerSprintTracker {
    public static final ServerSprintTracker INSTANCE = new ServerSprintTracker();

    private volatile boolean sprinting;

    private ServerSprintTracker() {
    }

    public static boolean isServerSprinting() {
        return INSTANCE.sprinting;
    }

    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPhase() != PacketSendEvent.Phase.POST
                || !(event.getPacket() instanceof ServerboundPlayerCommandPacket packet)) {
            return;
        }
        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
            this.sprinting = true;
        } else if (packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
            this.sprinting = false;
        }
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        this.sprinting = false;
        LocalPlayerHistory.reset();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        this.sprinting = false;
        LocalPlayerHistory.reset();
    }
}
