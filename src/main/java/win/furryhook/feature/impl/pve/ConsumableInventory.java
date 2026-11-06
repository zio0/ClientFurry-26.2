package win.furryhook.feature.impl.pve;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class ConsumableInventory {
    record Profile(
            ConsumableSelector.Kind kind,
            int nutrition,
            float saturation,
            boolean safe
    ) {
    }

    private ConsumableInventory() {
    }

    static List<ConsumableSelector.Candidate<ItemStack>> collect(
            LocalPlayer player,
            Function<ItemStack, Profile> classifier
    ) {
        List<ConsumableSelector.Candidate<ItemStack>> candidates = new ArrayList<>();
        int selectedHotbar = player.getInventory().getSelectedSlot();
        int selectedContainer = InventoryMenu.USE_ROW_SLOT_START + selectedHotbar;

        add(
                candidates,
                player.getOffhandItem(),
                ConsumableSelector.Location.OFF_HAND,
                InventoryMenu.SHIELD_SLOT,
                classifier
        );
        add(
                candidates,
                player.getMainHandItem(),
                ConsumableSelector.Location.MAIN_HAND,
                selectedContainer,
                classifier
        );

        for (int slot = InventoryMenu.USE_ROW_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            if (slot == selectedContainer) {
                continue;
            }
            add(
                    candidates,
                    player.inventoryMenu.getSlot(slot).getItem(),
                    ConsumableSelector.Location.HOTBAR,
                    slot,
                    classifier
            );
        }
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_START;
             slot++) {
            add(
                    candidates,
                    player.inventoryMenu.getSlot(slot).getItem(),
                    ConsumableSelector.Location.INVENTORY,
                    slot,
                    classifier
            );
        }
        return List.copyOf(candidates);
    }

    private static void add(
            List<ConsumableSelector.Candidate<ItemStack>> candidates,
            ItemStack stack,
            ConsumableSelector.Location location,
            int containerSlot,
            Function<ItemStack, Profile> classifier
    ) {
        if (stack.isEmpty()) {
            return;
        }
        Profile profile = classifier.apply(stack);
        if (profile == null) {
            return;
        }
        candidates.add(new ConsumableSelector.Candidate<>(
                stack.copy(),
                profile.kind(),
                location,
                containerSlot,
                profile.nutrition(),
                profile.saturation(),
                profile.safe()
        ));
    }
}
