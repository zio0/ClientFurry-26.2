package win.furryhook.pve.mining;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.UUID;

public final class MiningSessionSnapshot {
    private UUID playerId;
    private int selectedSlot = -1;
    private float yaw;
    private float pitch;
    private boolean captured;

    public void capture(LocalPlayer player) {
        if (player == null || this.captured) {
            return;
        }
        this.playerId = player.getUUID();
        this.selectedSlot = player.getInventory().getSelectedSlot();
        this.yaw = player.getYRot();
        this.pitch = player.getXRot();
        this.captured = true;
    }

    public void restore(Minecraft client) {
        if (!this.captured) {
            return;
        }
        LocalPlayer player = client == null ? null : client.player;
        if (player != null && player.getUUID().equals(this.playerId)) {
            if (this.selectedSlot >= 0 && this.selectedSlot < 9
                    && player.getInventory().getSelectedSlot() != this.selectedSlot) {
                player.getInventory().setSelectedSlot(this.selectedSlot);
                player.connection.send(new ServerboundSetCarriedItemPacket(this.selectedSlot));
            }
            player.setYRot(this.yaw);
            player.setXRot(this.pitch);
        }
        this.playerId = null;
        this.selectedSlot = -1;
        this.captured = false;
    }
}
