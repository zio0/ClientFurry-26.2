package win.furryhook.pve.navigation;

public record NavigationOptions(
        boolean allowBreak,
        boolean allowPlace,
        boolean allowSprint,
        boolean scanDroppedItems,
        int mineSearchRadius,
        boolean allowInteract,
        boolean fastMining,
        boolean protectClimbables,
        boolean strictBreakWhitelist,
        boolean rotateView
) {
    public NavigationOptions {
        mineSearchRadius = Math.max(0, mineSearchRadius);
    }

    public static NavigationOptions walking() {
        return new NavigationOptions(
                false, false, true, false, 0, true, false, true, false, true
        );
    }

    public static NavigationOptions mining() {
        return new NavigationOptions(
                true, true, true, true, 0, true, false, true, false, true
        );
    }

    public static NavigationOptions breakingOnly() {
        return new NavigationOptions(
                true, false, true, false, 15, false, true, true, true, true
        );
    }

    public NavigationOptions withViewRotation(boolean rotateView) {
        return new NavigationOptions(
                allowBreak,
                allowPlace,
                allowSprint,
                scanDroppedItems,
                mineSearchRadius,
                allowInteract,
                fastMining,
                protectClimbables,
                strictBreakWhitelist,
                rotateView
        );
    }
}
