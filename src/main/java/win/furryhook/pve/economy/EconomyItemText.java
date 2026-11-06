package win.furryhook.pve.economy;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public final class EconomyItemText {
    private EconomyItemText() {
    }

    public static List<String> lines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>();
        lines.add(stack.getHoverName().getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
        }
        return List.copyOf(lines);
    }

    public static String combined(ItemStack stack) {
        return String.join("\n", lines(stack));
    }

    public static boolean containsAny(ItemStack stack, String... markers) {
        return EconomyTextParser.containsAny(combined(stack), markers);
    }

    public static OptionalLong listingPrice(ItemStack stack) {
        long largest = -1L;
        for (String line : lines(stack)) {
            String normalized = EconomyTextParser.normalize(line);
            if (!EconomyTextParser.containsAny(
                    normalized,
                    "price",
                    "cost",
                    "цена",
                    "стоимость",
                    "за все",
                    "за штуку",
                    "монет",
                    "$"
            )) {
                continue;
            }
            OptionalLong amount = EconomyTextParser.largestAmount(normalized);
            if (amount.isPresent()) {
                largest = Math.max(largest, amount.getAsLong());
            }
        }
        return largest < 0L ? OptionalLong.empty() : OptionalLong.of(largest);
    }

    public static OptionalLong listingUnitPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalLong.empty();
        }
        long largest = -1L;
        for (String line : lines(stack)) {
            String normalized = EconomyTextParser.normalize(line);
            if (!EconomyTextParser.containsAny(
                    normalized,
                    "price",
                    "cost",
                    "цена",
                    "стоимость",
                    "за все",
                    "за штуку",
                    "per item",
                    "each",
                    "монет",
                    "$"
            )) {
                continue;
            }
            OptionalLong parsed = EconomyTextParser.largestAmount(normalized);
            if (parsed.isEmpty()) {
                continue;
            }
            boolean explicitlyPerItem = EconomyTextParser.containsAny(
                    normalized,
                    "за штуку",
                    "per item",
                    "each",
                    "1 шт"
            );
            long unit = explicitlyPerItem
                    ? parsed.getAsLong()
                    : Math.max(1L, parsed.getAsLong() / Math.max(1, stack.getCount()));
            largest = Math.max(largest, unit);
        }
        return largest < 0L ? OptionalLong.empty() : OptionalLong.of(largest);
    }
}
