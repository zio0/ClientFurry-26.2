package win.furryhook.feature.impl.combat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.mixin.accessor.KeyMappingAccessor;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class AutoClickerFeature extends Feature {
    public final BooleanSetting leftMouse = register(new BooleanSetting("Left Mouse", true));
    public final BooleanSetting rightMouse = register(new BooleanSetting("Right Mouse", false));
    public final ModeSetting clickMode = register(new ModeSetting("Mode", "Normal", "Normal", "Jitter", "Butterfly"));
    public final MultiSelectSetting targets = register(new MultiSelectSetting("Targets", List.of("Players", "Mobs"), "Players", "Mobs", "Animals", "Invisible"));
    public final NumberSetting cps = register(new NumberSetting("CPS", 12.0, 1.0, 20.0, 1.0, ""));

    private long nextLeftClickAt;
    private long nextRightClickAt;
    private boolean butterflyFast;

    public AutoClickerFeature() {
        super("AutoClicker", "Automatic mouse clicking", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        this.nextLeftClickAt = 0L;
        this.nextRightClickAt = 0L;
        this.butterflyFast = false;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        if (client.player == null
                || client.level == null
                || client.gui.screen() != null
                || client.player.isHandsBusy()) {
            return;
        }

        long now = System.nanoTime();
        if (this.leftMouse.getValue()
                && now >= this.nextLeftClickAt
                && validCrosshairTarget(client.crosshairPickEntity)) {
            click(client.options.keyAttack);
            this.nextLeftClickAt = now + nextDelayNanos();
        }
        if (this.rightMouse.getValue() && now >= this.nextRightClickAt) {
            click(client.options.keyUse);
            this.nextRightClickAt = now + nextDelayNanos();
        }
    }

    private boolean validCrosshairTarget(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (living.isInvisible() && !this.targets.isSelected("Invisible")) {
            return false;
        }
        if (living instanceof Player) {
            return this.targets.isSelected("Players");
        }
        if (living instanceof AgeableMob) {
            return this.targets.isSelected("Animals");
        }
        return living instanceof Mob && this.targets.isSelected("Mobs");
    }

    private long nextDelayNanos() {
        double baseMillis = 1000.0D / this.cps.getValue().doubleValue();
        double multiplier = switch (this.clickMode.getValue()) {
            case "Jitter" -> ThreadLocalRandom.current().nextDouble(0.85D, 1.16D);
            case "Butterfly" -> {
                this.butterflyFast = !this.butterflyFast;
                yield this.butterflyFast ? 0.65D : 1.35D;
            }
            default -> 1.0D;
        };
        return Math.max(1L, Math.round(baseMillis * multiplier * 1_000_000.0D));
    }

    private static void click(KeyMapping mapping) {
        KeyMapping.click(((KeyMappingAccessor) (Object) mapping).getKey());
    }
}
