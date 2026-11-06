package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * Strafe — горизонтальная скорость пишется напрямую, по направлению клавиш, с задаваемой
 * величиной.
 *
 * <p>Ванильное движение в воздухе почти не слушается клавиш: {@code aiStep} даёт в полёте
 * 0.02 ускорения за тик, всё остальное — инерция прыжка. Классический strafe не «ускоряет»,
 * он каждый тик заново задаёт вектор скорости из ввода, и поворот в воздухе перестаёт быть
 * заносом.
 *
 * <p>Три режима:
 *
 * <ul>
 *   <li><b>В воздухе</b> — скорость переписывается, только пока ноги не на земле и мы не
 *       планируем. Разгон на земле остаётся ванильным, так что бег, спринт и прыжок
 *       выглядят как обычно, а управление в прыжке становится жёстким.</li>
 *   <li><b>Всегда</b> — то же самое и на земле. Скольжения и трения нет вовсе.</li>
 *   <li><b>Элитра</b> — вектор не переписывается (это убило бы планирование), а к нему
 *       каждый тик добавляется боковая составляющая по A/D. Доворот корпусом на заходе,
 *       без потери курса.</li>
 * </ul>
 */
public final class StrafeFeature extends Feature {

    private static final String MODE_AIR = "В воздухе";
    private static final String MODE_ALWAYS = "Всегда";
    private static final String MODE_ELYTRA = "Элитра";

    /** Ниже этого ввод считается отпущенным. */
    private static final double INPUT_EPSILON = 1.0E-4D;

    public final ModeSetting mode = register(new ModeSetting(
            "Режим", MODE_AIR, MODE_AIR, MODE_ALWAYS, MODE_ELYTRA
    ));

    /** Блоков в секунду. Ванильный спринт-прыжок — около 5.6, ходьба — 4.3. */
    public final NumberSetting speed = register(new NumberSetting(
            "Скорость", 5.6D, 0.5D, 30.0D, 0.1D, " бл/с"
    ));

    /** Боковая добавка на элитре, блоков за тик. */
    public final NumberSetting elytraSpeed = register(new NumberSetting(
            "Боковая", 0.08D, 0.01D, 0.60D, 0.01D, " бл/т"
    ).visibleWhen(() -> mode.is(MODE_ELYTRA)));

    /**
     * Доля пути до заданной скорости, проходимая за один тик. 100% — мгновенная подмена
     * вектора (тот самый «резиновый» strafe), меньше — разгон, который выглядит как разгон.
     */
    public final NumberSetting accel = register(new NumberSetting(
            "Разгон", 100.0D, 5.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> !mode.is(MODE_ELYTRA)));

    /** Никогда не сбрасывать скорость ниже уже набранной — strafe только доворачивает. */
    public final BooleanSetting keepMomentum = register(new BooleanSetting(
            "Не тормозить", false
    ).visibleWhen(() -> !mode.is(MODE_ELYTRA)));

    public final BooleanSetting onlyWithInput = register(new BooleanSetting(
            "Только с вводом", true
    ));

    /** Держит спринт включённым, чтобы FOV и ванильные множители не спорили с подменой. */
    public final BooleanSetting holdSprint = register(new BooleanSetting(
            "Держать спринт", true
    ).visibleWhen(() -> !mode.is(MODE_ELYTRA)));

    public StrafeFeature() {
        super("Strafe", "Стрейф с задаваемой скоростью", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static StrafeFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(StrafeFeature.class);
    }

    /** Заданная скорость в блоках за тик. */
    public double speedPerTick() {
        return speed.getValue() / 20.0D;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isSpectator() || player.getAbilities().flying) {
            return;
        }
        if (player.isPassenger() || player.isInWater() || player.isInLava() || player.onClimbable()) {
            return;
        }

        Vec2 input = player.input.getMoveVector();
        double forward = input.y;
        double strafe = input.x;
        boolean moving = Math.abs(forward) > INPUT_EPSILON || Math.abs(strafe) > INPUT_EPSILON;
        if (!moving && onlyWithInput.getValue()) {
            return;
        }

        if (player.isFallFlying()) {
            if (mode.is(MODE_ELYTRA) && moving) {
                applyElytra(player, strafe);
            }
            return;
        }
        if (mode.is(MODE_ELYTRA)) {
            return;
        }
        if (mode.is(MODE_AIR) && player.onGround()) {
            return;
        }

        applyGround(player, forward, strafe, moving);
    }

    /**
     * Земля и воздух: вектор скорости пишется целиком, по направлению клавиш относительно
     * того курса, против которого движение и разрешается — то есть текущего {@code yaw}
     * игрока. Аура, спуфящая поворот, уже переписывает клавиши под свой курс сама, так что
     * читать здесь надо именно игрока, а не свободный вид.
     */
    private void applyGround(LocalPlayer player, double forward, double strafe, boolean moving) {
        Vec3 motion = player.getDeltaMovement();
        double wanted = speedPerTick();
        if (keepMomentum.getValue()) {
            wanted = Math.max(wanted, Math.hypot(motion.x, motion.z));
        }

        double current = Math.hypot(motion.x, motion.z);
        double blend = Mth.clamp(accel.getValue() / 100.0D, 0.05D, 1.0D);
        double magnitude = moving ? current + (wanted - current) * blend : current * (1.0D - blend);

        double direction = moveDirection(player.getYRot(), forward, strafe);
        double x = -Math.sin(direction) * magnitude;
        double z = Math.cos(direction) * magnitude;

        player.setDeltaMovement(x, motion.y, z);

        if (holdSprint.getValue() && moving && forward > 0.0D && player.getFoodData().getFoodLevel() > 6) {
            player.setSprinting(true);
        }
    }

    /**
     * Элитра: курс держит физика планирования, поэтому переписывать вектор нельзя — это
     * мгновенно съедает подъёмную силу. Добавляется только боковая составляющая,
     * перпендикулярная взгляду, и ровно на величину настройки за тик.
     */
    private void applyElytra(LocalPlayer player, double strafe) {
        if (Math.abs(strafe) <= INPUT_EPSILON) {
            return;
        }

        // Курс «вперёд» в горизонтали — (-sin yaw, cos yaw), значит «вправо» — (cos yaw, sin yaw).
        double yaw = Math.toRadians(player.getYRot());
        double x = Math.cos(yaw);
        double z = Math.sin(yaw);

        double step = elytraSpeed.getValue() * Math.signum(strafe);
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x + x * step, motion.y, motion.z + z * step);
    }

    /**
     * Ванильная развёртка пары «вперёд/вбок» в один курс движения, в радианах. Совпадает с
     * тем, что делает {@code LocalPlayer.aiStep} со своими клавишами, поэтому подменённый
     * вектор идёт ровно туда, куда игрок и жал.
     */
    private static double moveDirection(float yaw, double forward, double strafe) {
        float heading = yaw;
        if (forward < 0.0D) {
            heading += 180.0F;
        }

        float sign = 1.0F;
        if (forward < 0.0D) {
            sign = -0.5F;
        } else if (forward > 0.0D) {
            sign = 0.5F;
        }

        if (strafe > 0.0D) {
            heading -= 90.0F * sign;
        }
        if (strafe < 0.0D) {
            heading += 90.0F * sign;
        }

        return Math.toRadians(heading);
    }
}
