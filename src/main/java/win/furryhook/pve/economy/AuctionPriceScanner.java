package win.furryhook.pve.economy;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import win.furryhook.utils.inventory.ContainerLootService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.OptionalLong;

public final class AuctionPriceScanner {
    private AuctionPriceScanner() {
    }

    public static OptionalLong competitivePrice(AbstractContainerMenu menu,
                                                Item target,
                                                String query,
                                                int saleCount) {
        if (menu == null || target == null || saleCount <= 0) {
            return OptionalLong.empty();
        }
        ArrayList<Long> unitPrices = new ArrayList<>();
        int containerSlots = ContainerLootService.containerSlotCount(menu);
        for (int slotId = 0; slotId < containerSlots; slotId++) {
            if (!menu.isValidSlotIndex(slotId)) {
                continue;
            }
            ItemStack stack = menu.getSlot(slotId).getItem();
            if (stack.isEmpty()
                    || !stack.is(target)
                    && !EconomyItemText.containsAny(stack, query)) {
                continue;
            }
            EconomyItemText.listingUnitPrice(stack).ifPresent(unitPrices::add);
        }
        if (unitPrices.isEmpty()) {
            return OptionalLong.empty();
        }
        Collections.sort(unitPrices);
        long unitMedian = unitPrices.get(unitPrices.size() / 2);
        long total;
        try {
            total = Math.multiplyExact(unitMedian, saleCount);
        } catch (ArithmeticException ignored) {
            total = Integer.MAX_VALUE;
        }
        long competitive = Math.max(1L, Math.min(Integer.MAX_VALUE, total - 1L));
        return OptionalLong.of(competitive);
    }
}
