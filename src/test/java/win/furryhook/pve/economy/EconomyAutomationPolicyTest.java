package win.furryhook.pve.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomyAutomationPolicyTest {
    @Test
    void crafterTransitionsHaveStablePriority() {
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.SELL,
                EconomyAutomationPolicy.crafterAction(64, 1, 8, 0, true, true, true)
        );
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.CRAFT_APPLE,
                EconomyAutomationPolicy.crafterAction(0, 1, 8, 0, true, true, true)
        );
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.CRAFT_GOLD_BLOCK,
                EconomyAutomationPolicy.crafterAction(0, 1, 2, 9, true, true, true)
        );
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.TAKE_APPLES,
                EconomyAutomationPolicy.crafterAction(0, 0, 0, 0, true, true, true)
        );
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.TAKE_GOLD,
                EconomyAutomationPolicy.crafterAction(0, 1, 0, 0, true, true, true)
        );
        assertEquals(
                EconomyAutomationPolicy.CrafterAction.WAIT,
                EconomyAutomationPolicy.crafterAction(1, 0, 0, 0, false, false, true)
        );
    }

    @Test
    void tradeTransitionsPreferOutputProcessingBeforeNewPurchases() {
        assertEquals(
                EconomyAutomationPolicy.TradeAction.SELL_BLOCKS,
                EconomyAutomationPolicy.tradeAction(
                        192, 18, 64, 192,
                        true, true, true, true,
                        false, true, true, true
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.CRAFT_BLOCKS,
                EconomyAutomationPolicy.tradeAction(
                        192, 9, 0, 192,
                        true, true, true, true,
                        false, true, false, false
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.DEPOSIT_GOLD,
                EconomyAutomationPolicy.tradeAction(
                        0, 1, 0, 192,
                        true, true, false, false,
                        true, true, true, false
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.TRADE,
                EconomyAutomationPolicy.tradeAction(
                        192, 0, 0, 192,
                        true, true, true, true,
                        false, true, true, false
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.BUY_EMERALDS,
                EconomyAutomationPolicy.tradeAction(
                        64, 0, 0, 192,
                        true, true, true, true,
                        false, true, true, false
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.DEPOSIT_GOLD,
                EconomyAutomationPolicy.tradeAction(
                        64, 1, 0, 192,
                        false, true, true, true,
                        false, true, true, false
                )
        );
        assertEquals(
                EconomyAutomationPolicy.TradeAction.WAIT,
                EconomyAutomationPolicy.tradeAction(
                        64, 0, 0, 192,
                        false, true, true, true,
                        false, true, true, false
                )
        );
    }
}
