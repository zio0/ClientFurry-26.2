package win.furryhook.pve.economy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.Objects;
import java.util.function.Predicate;

public final class EconomyMenus {
    private EconomyMenus() {
    }

    public static String title(Minecraft client) {
        if (client != null
                && client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            return screen.getTitle().getString();
        }
        return "";
    }

    public static boolean titleContains(Minecraft client, String... markers) {
        return EconomyTextParser.containsAny(title(client), markers);
    }

    public static int currentContainerId(Minecraft client) {
        return client != null && client.player != null && client.player.containerMenu != null
                ? client.player.containerMenu.containerId
                : -1;
    }

    public static boolean isCurrent(Minecraft client, AbstractContainerMenu menu) {
        return client != null
                && client.player != null
                && menu != null
                && client.player.containerMenu == menu
                && currentContainerId(client) == menu.containerId;
    }

    public static int findContainerSlot(AbstractContainerMenu menu,
                                        Predicate<ItemStack> predicate) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(predicate, "predicate");
        int count = ContainerLootService.containerSlotCount(menu);
        for (int slotId = 0; slotId < count; slotId++) {
            if (!menu.isValidSlotIndex(slotId)) {
                continue;
            }
            Slot slot = menu.getSlot(slotId);
            if (slot != null && slot.hasItem() && predicate.test(slot.getItem())) {
                return slotId;
            }
        }
        return -1;
    }

    public static boolean click(Minecraft client,
                                AbstractContainerMenu menu,
                                int slotId,
                                int button,
                                ContainerInput input) {
        if (!isCurrent(client, menu)
                || !menu.isValidSlotIndex(slotId)
                || input == null) {
            return false;
        }
        return InventoryUtil.clickSlot(slotId, button, input);
    }

    public static boolean quickMove(Minecraft client,
                                    AbstractContainerMenu menu,
                                    int slotId) {
        return click(client, menu, slotId, 0, ContainerInput.QUICK_MOVE);
    }

    public static boolean closeOwned(Minecraft client, int containerId) {
        if (client == null
                || client.player == null
                || containerId < 0
                || currentContainerId(client) != containerId
                || client.player.containerMenu == client.player.inventoryMenu) {
            return false;
        }
        client.player.closeContainer();
        return true;
    }
}
