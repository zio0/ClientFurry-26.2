package win.furryhook.feature.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * CrystalOptimizer — снимает нагрузку с отрисовки эндер-кристаллов.
 *
 * <p>Кристалл рисуется тремя слоями с прозрачностью и лучом к цели, и на плотном краудинге их
 * бывает сотни. Модуль режет то, что не видно и не нужно: всё дальше заданной дистанции, и по
 * желанию — вообще все кристаллы разом.
 *
 * <p>Решение принимается в {@link win.furryhook.mixin.render.EndCrystalRendererMixin} на
 * {@code shouldRender}, то есть до построения состояния и до отправки в очередь отрисовки.
 */
public final class CrystalOptimizerFeature extends Feature {

    public final NumberSetting renderDistance = register(new NumberSetting(
            "Дистанция", 24.0D, 4.0D, 128.0D, 1.0D, " бл"
    ));

    public final BooleanSetting hideAll = register(new BooleanSetting(
            "Скрыть все", false
    ));

    public CrystalOptimizerFeature() {
        super("CrystalOptimizer", "Режет отрисовку эндер-кристаллов", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public static CrystalOptimizerFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(CrystalOptimizerFeature.class);
    }

    /** False — кристалл в этом кадре не рисуется. */
    public static boolean shouldRender(EndCrystal crystal) {
        CrystalOptimizerFeature feature = getEnabled();
        if (feature == null || crystal == null) {
            return true;
        }
        if (feature.hideAll.getValue()) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }
        double limit = feature.renderDistance.getValue();
        return player.distanceToSqr(crystal) <= limit * limit;
    }
}
