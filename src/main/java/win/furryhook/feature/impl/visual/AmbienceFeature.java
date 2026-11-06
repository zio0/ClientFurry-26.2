package win.furryhook.feature.impl.visual;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.util.Mth;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;

/**
 * Ambience - порт javelin {@code tech.javelin.features.module.impl.visual.WorldTime}.
 *
 * <p>Две вещи, обе про то, как выглядит мир, и обе - жёсткая подмена, без анимаций:
 *
 * <ul>
 *   <li><b>Время суток.</b> Пакет {@link ClientboundSetTimePacket} режется на входе, а часы мира
 *       возвращают выбранное время. Резать пакет обязательно: без этого сервер каждые несколько
 *       секунд возвращает своё время, и любая клиентская подмена мигает вместе с ним.</li>
 *   <li><b>Туман.</b> Раздельные начало и конец, чего у {@link WorldTweaksFeature} нет - тот
 *       выводит начало из конца и получает одну и ту же плотность на любой дистанции. Небесный
 *       туман уводится на 1.35 от конца, облачный на 1.15: если их оставить на общем конце,
 *       горизонт срезается ровно там же, где земля, и небо выглядит приклеенным.</li>
 * </ul>
 *
 * <p><b>Конфликт:</b> {@code WorldTweaks} умеет то же самое своими галками "Change Time" и
 * "Change Fog". Оба модуля пишут в один и тот же {@code FogData} и одни и те же часы, поэтому
 * приоритет отдан этому - он включается первым и {@code WorldTweaks} на эти два поля не пускает.
 * Остальное в {@code WorldTweaks} (небо, насыщенность, цвет мира) работает как работало.
 */
public final class AmbienceFeature extends Feature {

    private static final String FOG_MODE_VANILLA = "Ванила";
    private static final String FOG_MODE_THEME = "От темы";

    public final ModeSetting timeMode = register(new ModeSetting(
            "Time of Day",
            "Day",
            "Dawn", "Morning", "Day", "Evening", "Sunset", "Night"
    ).configKey("render.ambience.time"));

    public final BooleanSetting fog = register(new BooleanSetting(
            "Туман",
            true
    ).configKey("render.ambience.fog"));

    public final ModeSetting fogMode = register(new ModeSetting(
            "Режим тумана",
            FOG_MODE_VANILLA,
            FOG_MODE_VANILLA, FOG_MODE_THEME
    ).configKey("render.ambience.fog.mode").visibleWhen(() -> this.fog.getValue()));

    public final NumberSetting fogStart = register(new NumberSetting(
            "Начало тумана",
            8.0D, 0.0D, 128.0D, 1.0D, " бл"
    ).configKey("render.ambience.fog.start").visibleWhen(() -> this.fog.getValue()));

    public final NumberSetting fogEnd = register(new NumberSetting(
            "Конец тумана",
            72.0D, 8.0D, 256.0D, 1.0D, " бл"
    ).configKey("render.ambience.fog.end").visibleWhen(() -> this.fog.getValue()));

    public AmbienceFeature() {
        super("Ambience", "Время суток и туман", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public static AmbienceFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(AmbienceFeature.class);
    }

    /**
     * Сервер шлёт своё время сам по себе, не только в ответ на что-то. Пока пакет не срезан,
     * подменённые часы возвращаются к серверным при каждой такой рассылке.
     */
    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() == PacketReceiveEvent.Phase.PRE
                && event.getPacket() instanceof ClientboundSetTimePacket) {
            event.cancel();
        }
    }

    /** Тик внутри суток, на который встают часы мира. */
    public long getTargetTime() {
        return switch (this.timeMode.getValue()) {
            case "Dawn" -> 23_000L;
            case "Morning" -> 1_000L;
            case "Evening" -> 12_000L;
            case "Sunset" -> 13_000L;
            case "Night" -> 18_000L;
            default -> 6_000L;
        };
    }

    public boolean usesFog() {
        return this.fog.getValue();
    }

    /**
     * Ближняя граница тумана. Держится на блок ниже дальней: равные или перевёрнутые границы дают
     * деление на ноль в интерполяции тумана и белый экран.
     */
    public float resolvedFogStart() {
        return Mth.clamp(this.fogStart.getValue().floatValue(), 0.0F, resolvedFogEnd() - 1.0F);
    }

    public float resolvedFogEnd() {
        return Math.max(this.fogStart.getValue().floatValue() + 1.0F, this.fogEnd.getValue().floatValue());
    }

    public void applyFog(FogData data) {
        float start = resolvedFogStart();
        float end = resolvedFogEnd();

        data.environmentalStart = start;
        data.environmentalEnd = end;
        data.renderDistanceStart = start;
        data.renderDistanceEnd = end;
        // Небо и облака уходят дальше земли, иначе горизонт срезается на той же дистанции и небо
        // читается как плоская заливка вплотную к игроку.
        data.skyEnd = end * 1.35F;
        data.cloudEnd = end * 1.15F;

        if (this.fogMode.is(FOG_MODE_THEME)) {
            int color = Theme.getAccent();
            data.color.set(
                    ColorUtil.red(color) / 255.0F,
                    ColorUtil.green(color) / 255.0F,
                    ColorUtil.blue(color) / 255.0F,
                    1.0F
            );
        }
    }
}
