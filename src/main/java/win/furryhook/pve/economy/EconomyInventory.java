package win.furryhook.pve.economy;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.function.Predicate;

public final class EconomyInventory {
    private EconomyInventory() {
    }

    public static int count(LocalPlayer player, Item item) {
        if (player == null || item == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int findHotbar(LocalPlayer player, Predicate<ItemStack> predicate) {
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (predicate.test(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean selectHotbar(LocalPlayer player, int slot) {
        if (player == null || slot < 0 || slot > 8) {
            return false;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
        return true;
    }

    public static boolean moveFirstToSelectedHotbar(LocalPlayer player,
                                                    Predicate<ItemStack> predicate) {
        if (player == null || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        int menuSlot = InventoryUtil.findPlayerMenuSlot(player, predicate);
        if (menuSlot < 0) {
            return false;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (menuSlot >= 36 && menuSlot <= 44) {
            return selectHotbar(player, menuSlot - 36);
        }
        return InventoryUtil.swapWithHotbar(menuSlot, selected);
    }

    public static boolean hasFreeSlot(LocalPlayer player) {
        if (player == null) {
            return false;
        }
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
