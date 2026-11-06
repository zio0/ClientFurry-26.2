package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class TimerFeature extends Feature {
    public final ModeSetting mode = register(new ModeSetting("Mode", "Balance", "Classic", "Balance"));
    public final NumberSetting speed = register(new NumberSetting("Speed", 1.05, 1.0, 1.2, 0.01, "x"));
    public final NumberSetting slowSpeed = register(new NumberSetting("Slow Speed", 0.6, 0.1, 0.99, 0.01, "x"));
    public final NumberSetting boostSpeed = register(new NumberSetting("Boost Speed", 1.2, 1.0, 1.2, 0.01, "x"));
    public final NumberSetting balanceCap = register(new NumberSetting("Balance Cap", 12, 1, 60, 1, " ticks"));
    private double rateAccumulator;
    private double slowAccumulator;
    private int balanceTicks;

    public TimerFeature() {
        super("Timer", "Changes the local tick rate", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static boolean shouldSkipBaseTick() {
        TimerFeature timer = FeatureManager.INSTANCE.getEnabled(TimerFeature.class);
        LocalPlayer player = Minecraft.getInstance().player;
        if (timer == null || player == null || !timer.mode.is("Balance") || hasMovementInput(player)) {
            return false;
        }

        timer.slowAccumulator += timer.slowSpeed.getValue();
        if (timer.slowAccumulator >= 1.0) {
            timer.slowAccumulator -= 1.0;
            return false;
        }

        timer.balanceTicks = Math.min(timer.balanceTicks + 1, timer.balanceCap.getValue().intValue());
        return true;
    }

    public static boolean consumeExtraTick() {
        TimerFeature timer = FeatureManager.INSTANCE.getEnabled(TimerFeature.class);
        if (timer == null) {
            return false;
        }

        double rate = timer.mode.is("Balance") ? timer.boostSpeed.getValue() : timer.speed.getValue();
        if (timer.mode.is("Balance") && (timer.balanceTicks == 0 || !hasMovementInput(Minecraft.getInstance().player))) {
            return false;
        }

        timer.rateAccumulator += rate - 1.0;
        if (timer.rateAccumulator < 1.0) {
            return false;
        }

        timer.rateAccumulator -= 1.0;
        if (timer.mode.is("Balance")) {
            timer.balanceTicks--;
        }
        return true;
    }

    @Override
    protected void onDisable() {
        rateAccumulator = 0.0;
        slowAccumulator = 0.0;
        balanceTicks = 0;
    }

    private static boolean hasMovementInput(LocalPlayer player) {
        return player != null && player.input.getMoveVector().lengthSquared() > 0.0F;
    }
}
