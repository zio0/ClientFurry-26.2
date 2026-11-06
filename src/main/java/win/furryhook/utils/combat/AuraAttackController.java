package win.furryhook.utils.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class AuraAttackController {
    private int restoreSlot = -1;
    private int temporarySlot = -1;
    private int useKeyRestoreTicks = -1;

    public void releaseShieldBeforeAttack(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null
                || this.useKeyRestoreTicks >= 0
                || !player.isUsingItem()
                || player.getItemBlockingWith() == null
                || !minecraft.options.keyUse.isDown()) {
            return;
        }
        minecraft.gameMode.releaseUsingItem(player);
        minecraft.options.keyUse.setDown(false);
        this.useKeyRestoreTicks = 0;
    }

    public boolean attack(LocalPlayer player, LivingEntity target, boolean breakShield) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || player == null || target == null || !target.isAlive()) {
            return false;
        }

        AttackWindow.attack(target);

        if (breakShield && target.isBlocking()) {
            axeFollowUp(minecraft, player, target);
        }
        return true;
    }

    public void tick(LocalPlayer player) {
        if (this.useKeyRestoreTicks >= 0 && --this.useKeyRestoreTicks < 0) {
            Minecraft.getInstance().options.keyUse.setDown(true);
        }

        if (this.restoreSlot < 0 || player == null) {
            return;
        }
        if (player.getInventory().getSelectedSlot() == this.temporarySlot) {
            player.getInventory().setSelectedSlot(this.restoreSlot);
        }
        clearRestore();
    }

    public void reset(LocalPlayer player) {
        if (player != null
                && this.restoreSlot >= 0
                && player.getInventory().getSelectedSlot() == this.temporarySlot) {
            player.getInventory().setSelectedSlot(this.restoreSlot);
        }
        clearRestore();
        this.useKeyRestoreTicks = -1;
    }

    private void axeFollowUp(Minecraft minecraft, LocalPlayer player, LivingEntity target) {
        int axeSlot = findHotbarAxe(player);
        if (axeSlot < 0) {
            return;
        }
        int selectedSlot = player.getInventory().getSelectedSlot();
        if (axeSlot != selectedSlot) {
            player.getInventory().setSelectedSlot(axeSlot);
            this.restoreSlot = selectedSlot;
            this.temporarySlot = axeSlot;
        }
        minecraft.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private int findHotbarAxe(LocalPlayer player) {
        int selectedSlot = player.getInventory().getSelectedSlot();
        if (isAxe(player.getInventory().getItem(selectedSlot))) {
            return selectedSlot;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isAxe(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isAxe(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.AXES);
    }

    private void clearRestore() {
        this.restoreSlot = -1;
        this.temporarySlot = -1;
    }
}
