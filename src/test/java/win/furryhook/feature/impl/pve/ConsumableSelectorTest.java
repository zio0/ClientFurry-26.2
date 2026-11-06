package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumableSelectorTest {
    @Test
    void heldFoodAvoidsAnInventorySwap() {
        var selected = ConsumableSelector.selectFood(
                List.of(
                        candidate(
                                "steak",
                                ConsumableSelector.Kind.FOOD,
                                ConsumableSelector.Location.INVENTORY,
                                10,
                                8,
                                0.8F,
                                true
                        ),
                        candidate(
                                "carrot",
                                ConsumableSelector.Kind.FOOD,
                                ConsumableSelector.Location.OFF_HAND,
                                45,
                                3,
                                0.6F,
                                true
                        )
                ),
                false,
                false
        );

        assertEquals("carrot", selected.orElseThrow().value());
    }

    @Test
    void inventoryFoodOrdersSafeThenAppleThenHarmful() {
        var candidates = List.of(
                candidate(
                        "rotten",
                        ConsumableSelector.Kind.FOOD,
                        ConsumableSelector.Location.HOTBAR,
                        36,
                        20,
                        1.0F,
                        false
                ),
                candidate(
                        "gapple",
                        ConsumableSelector.Kind.GOLDEN_APPLE,
                        ConsumableSelector.Location.HOTBAR,
                        37,
                        20,
                        1.0F,
                        true
                ),
                candidate(
                        "bread",
                        ConsumableSelector.Kind.FOOD,
                        ConsumableSelector.Location.INVENTORY,
                        9,
                        2,
                        0.1F,
                        true
                )
        );

        assertEquals(
                "bread",
                ConsumableSelector.selectFood(candidates, false, false)
                        .orElseThrow()
                        .value()
        );
        assertEquals(
                "gapple",
                ConsumableSelector.selectFood(candidates.subList(0, 2), false, false)
                        .orElseThrow()
                        .value()
        );
    }

    @Test
    void strongestFoodWinsWithinTheSameSafetyGroup() {
        var selected = ConsumableSelector.selectFood(
                List.of(
                        candidate(
                                "bread",
                                ConsumableSelector.Kind.FOOD,
                                ConsumableSelector.Location.HOTBAR,
                                36,
                                5,
                                0.6F,
                                true
                        ),
                        candidate(
                                "steak",
                                ConsumableSelector.Kind.FOOD,
                                ConsumableSelector.Location.INVENTORY,
                                9,
                                8,
                                0.8F,
                                true
                        )
                ),
                false,
                false
        );

        assertEquals("steak", selected.orElseThrow().value());
    }

    @Test
    void foodIgnoreSettingsApplyToEachAppleType() {
        var candidates = List.of(
                candidate(
                        "normal",
                        ConsumableSelector.Kind.GOLDEN_APPLE,
                        ConsumableSelector.Location.HOTBAR,
                        36,
                        4,
                        1.2F,
                        true
                ),
                candidate(
                        "enchanted",
                        ConsumableSelector.Kind.ENCHANTED_GOLDEN_APPLE,
                        ConsumableSelector.Location.INVENTORY,
                        9,
                        4,
                        1.2F,
                        true
                )
        );

        assertEquals(
                "enchanted",
                ConsumableSelector.selectFood(candidates, true, false)
                        .orElseThrow()
                        .value()
        );
        assertEquals(
                "normal",
                ConsumableSelector.selectFood(candidates, false, true)
                        .orElseThrow()
                        .value()
        );
        assertTrue(ConsumableSelector.selectFood(candidates, true, true).isEmpty());
    }

    @Test
    void enchantedAppleWinsWhenAllowedRegardlessOfLocation() {
        var selected = ConsumableSelector.selectApple(
                List.of(
                        candidate(
                                "normal",
                                ConsumableSelector.Kind.GOLDEN_APPLE,
                                ConsumableSelector.Location.OFF_HAND,
                                45,
                                0,
                                0.0F,
                                true
                        ),
                        candidate(
                                "enchanted",
                                ConsumableSelector.Kind.ENCHANTED_GOLDEN_APPLE,
                                ConsumableSelector.Location.INVENTORY,
                                9,
                                0,
                                0.0F,
                                true
                        )
                ),
                true,
                true
        );

        assertEquals("enchanted", selected.orElseThrow().value());
    }

    @Test
    void appleAllowListFallsBackAndCanDisableAll() {
        var candidates = List.of(
                candidate(
                        "normal",
                        ConsumableSelector.Kind.GOLDEN_APPLE,
                        ConsumableSelector.Location.HOTBAR,
                        36,
                        0,
                        0.0F,
                        true
                ),
                candidate(
                        "enchanted",
                        ConsumableSelector.Kind.ENCHANTED_GOLDEN_APPLE,
                        ConsumableSelector.Location.HOTBAR,
                        37,
                        0,
                        0.0F,
                        true
                )
        );

        assertEquals(
                "normal",
                ConsumableSelector.selectApple(candidates, true, false)
                        .orElseThrow()
                        .value()
        );
        assertTrue(ConsumableSelector.selectApple(candidates, false, false).isEmpty());
    }

    private static ConsumableSelector.Candidate<String> candidate(
            String value,
            ConsumableSelector.Kind kind,
            ConsumableSelector.Location location,
            int slot,
            int nutrition,
            float saturation,
            boolean safe
    ) {
        return new ConsumableSelector.Candidate<>(
                value,
                kind,
                location,
                slot,
                nutrition,
                saturation,
                safe
        );
    }
}
