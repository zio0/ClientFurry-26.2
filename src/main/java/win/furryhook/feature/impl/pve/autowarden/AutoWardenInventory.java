package win.furryhook.feature.impl.pve.autowarden;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.Set;
import java.util.function.Predicate;

final class AutoWardenInventory {
    private static final int PLAYER_INVENTORY_SIZE = 36;
    private static final Set<Item> RESTOCKABLE_FOOD = Set.of(
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_RABBIT,
            Items.GOLDEN_CARROT,
            Items.BREAD,
            Items.BAKED_POTATO
    );

    private AutoWardenInventory() {
    }

    static int countInvisibility(LocalPlayer player) {
        return count(player, AutoWardenInventory::isInvisibilityPotion);
    }

    static int countSpeed(LocalPlayer player) {
        return count(player, AutoWardenInventory::isSpeedPotion);
    }

    static int countFood(LocalPlayer player) {
        return count(player, AutoWardenInventory::isRestockableFood);
    }

    static int freeSlots(LocalPlayer player) {
        int free = 0;
        for (int index = 0; index < PLAYER_INVENTORY_SIZE; index++) {
            if (player.getInventory().getItem(index).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    static int countValuables(LocalPlayer player) {
        return count(player, AutoWardenInventory::isValuable);
    }

    static boolean carryingValuables(LocalPlayer player) {
        for (int index = 0; index < PLAYER_INVENTORY_SIZE; index++) {
            if (isValuable(player.getInventory().getItem(index))) {
                return true;
            }
        }
        return false;
    }

    static boolean isInvisibilityPotion(ItemStack stack) {
        return isPotionWith(stack, MobEffects.INVISIBILITY, false);
    }

    static boolean isSpeedPotion(ItemStack stack) {
        return isPotionWith(stack, MobEffects.SPEED, true);
    }

    static boolean isRestockableFood(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.has(DataComponents.FOOD)
                && RESTOCKABLE_FOOD.contains(stack.getItem());
    }

    static boolean isSupply(ItemStack stack) {
        return isInvisibilityPotion(stack) || isSpeedPotion(stack) || isRestockableFood(stack);
    }

    static boolean isValuable(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && !stack.is(Items.TRIPWIRE_HOOK)
                && !stack.is(Items.GLASS_BOTTLE)
                && !isSupply(stack);
    }

    static boolean quickMoveFirstContainerItem(AbstractContainerMenu menu,
                                               Predicate<ItemStack> predicate) {
        return ContainerLootService.quickMoveFirst(menu, predicate);
    }

    static boolean quickMoveFirstPlayerItem(AbstractContainerMenu menu,
                                            Predicate<ItemStack> predicate) {
        int containerSlots = ContainerLootService.containerSlotCount(menu);
        for (int slotId = containerSlots; slotId < menu.slots.size(); slotId++) {
            Slot slot = menu.getSlot(slotId);
            if (slot.hasItem() && predicate.test(slot.getItem())) {
                return InventoryUtil.quickMoveSlot(slotId);
            }
        }
        return false;
    }

    static int findPlayerMenuSlot(LocalPlayer player, Predicate<ItemStack> predicate) {
        return InventoryUtil.findPlayerMenuSlot(player, predicate);
    }

    static int findEmptyHotbarSlot(LocalPlayer player, int excludedSlot) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot != excludedSlot && player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    static int findSafeHotbarSlot(LocalPlayer player, int excludedSlot) {
        int empty = findEmptyHotbarSlot(player, excludedSlot);
        if (empty >= 0) {
            return empty;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != excludedSlot
                    && !stack.is(Items.TRIPWIRE_HOOK)
                    && !isSupply(stack)) {
                return slot;
            }
        }
        return -1;
    }

    static boolean dropFirstBottle(LocalPlayer player) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        int menuSlot = InventoryUtil.findPlayerMenuSlot(player, stack -> stack.is(Items.GLASS_BOTTLE));
        if (menuSlot < 0) {
            return false;
        }
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                1,
                ContainerInput.THROW,
                player
        );
        return true;
    }

    private static int count(LocalPlayer player, Predicate<ItemStack> predicate) {
        int count = 0;
        for (int index = 0; index < PLAYER_INVENTORY_SIZE; index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isPotionWith(ItemStack stack,
                                        net.minecraft.core.Holder<MobEffect> wanted,
                                        boolean drinkableOnly) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (drinkableOnly && !stack.is(Items.POTION)) {
            return false;
        }
        if (!stack.is(Items.POTION)
                && !stack.is(Items.SPLASH_POTION)
                && !stack.is(Items.LINGERING_POTION)) {
            return false;
        }
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        int effects = 0;
        boolean found = false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            effects++;
            if (effect.getEffect().equals(wanted)) {
                found = true;
            }
        }
        return found && (!drinkableOnly || effects >= 1);
    }
}
