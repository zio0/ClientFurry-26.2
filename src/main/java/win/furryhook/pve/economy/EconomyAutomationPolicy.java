package win.furryhook.pve.economy;

public final class EconomyAutomationPolicy {
    private EconomyAutomationPolicy() {
    }

    public static CrafterAction crafterAction(int output,
                                              int apples,
                                              int goldBlocks,
                                              int goldIngots,
                                              boolean autoSell,
                                              boolean canTakeResources,
                                              boolean auctionReady) {
        if (autoSell
                && auctionReady
                && output > 0
                && (output >= 64
                || !canTakeResources && (apples < 1 || goldBlocks < 8 && goldIngots < 9))) {
            return CrafterAction.SELL;
        }
        if (apples >= 1 && goldBlocks >= 8) {
            return CrafterAction.CRAFT_APPLE;
        }
        if (goldBlocks < 8 && goldIngots >= 9) {
            return CrafterAction.CRAFT_GOLD_BLOCK;
        }
        if (canTakeResources) {
            return apples < 1 ? CrafterAction.TAKE_APPLES : CrafterAction.TAKE_GOLD;
        }
        return CrafterAction.WAIT;
    }

    public static TradeAction tradeAction(int emeralds,
                                          int goldIngots,
                                          int goldBlocks,
                                          int emeraldTarget,
                                          boolean buyEmeralds,
                                          boolean depositGold,
                                          boolean craftBlocks,
                                          boolean sellBlocks,
                                          boolean moneyDry,
                                          boolean shopReady,
                                          boolean auctionReady,
                                          boolean hasSellableBlockStack) {
        if (sellBlocks && auctionReady && hasSellableBlockStack) {
            return TradeAction.SELL_BLOCKS;
        }
        if (craftBlocks && goldIngots >= 9) {
            return TradeAction.CRAFT_BLOCKS;
        }
        if (moneyDry && depositGold && (goldIngots > 0 || goldBlocks > 0)) {
            return TradeAction.DEPOSIT_GOLD;
        }
        if (emeralds >= emeraldTarget) {
            return TradeAction.TRADE;
        }
        if (buyEmeralds && shopReady) {
            return TradeAction.BUY_EMERALDS;
        }
        if (depositGold && (goldIngots > 0 || goldBlocks > 0)) {
            return TradeAction.DEPOSIT_GOLD;
        }
        return TradeAction.WAIT;
    }

    public enum CrafterAction {
        SELL,
        CRAFT_APPLE,
        CRAFT_GOLD_BLOCK,
        TAKE_APPLES,
        TAKE_GOLD,
        WAIT
    }

    public enum TradeAction {
        SELL_BLOCKS,
        CRAFT_BLOCKS,
        DEPOSIT_GOLD,
        TRADE,
        BUY_EMERALDS,
        WAIT
    }
}
