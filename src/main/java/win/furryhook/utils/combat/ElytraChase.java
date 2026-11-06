package win.furryhook.utils.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Наведение перегона: куда смотреть, чтобы элитра действительно догнала.
 *
 * <p>Перегон «смотри в цель и лети» не работает по двум причинам, и обе лежат в
 * {@link ElytraPhysics}:
 *
 * <ul>
 *   <li><b>Курс доворачивает 10% за тик.</b> Голова, наведённая ровно в цель, задаёт вектору
 *       скорости ошибку, которую физика гасит первым порядком: заход получается по кривой погони,
 *       а по кривой погони пересекающегося игрока не догнать никогда. Чтобы <i>скорость</i>
 *       повернула на X градусов за тик, голова обязана уйти примерно на X/0.1 градусов дальше
 *       нужного курса. Это и делает {@code steerGain}: ошибка курса умножается, а не отрабатывается
 *       один в один.</li>
 *   <li><b>Тангаж - это газ и тормоз, а не прицел.</b> Нос вниз - разгон за счёт высоты, нос вверх -
 *       единственный тормоз в тике. Голова, наведённая в цель, которая выше, автоматически тормозит
 *       и повисает; наведённая в цель, которая ниже, разгоняет в землю. Поэтому тангаж здесь
 *       считается не прицелом, а регулятором скорости: к прямому углу на точку встречи добавляется
 *       поправка по разнице «нужная скорость минус своя».</li>
 * </ul>
 *
 * <p>Точка встречи считается не дугой предикта, а решением «когда сфера цели и наша сойдутся»:
 * |P + V*t| = s*t относительно нас. Дуговой предикт javelin на дистанции перегона даёт до сорока
 * тиков упреждения ({@code ticks = dist * dist/100 + 3}), поворачивает вектор цели на её угловую
 * дельту сорок раз подряд и уезжает по спирали в чистое небо - голова дёргается за этой спиралью
 * вместо того, чтобы вести заход. Дуга остаётся там, где она работает: на последних блоках, где
 * тиков упреждения три.
 *
 * <p>Ничего не пишет: только считает, куда смотреть.
 */
public final class ElytraChase {

    /**
     * Множитель ошибки курса. Единица - «смотреть в цель», то есть кривая погони; десять - предел,
     * за которым доворот физики уже не растёт. Пять держит заход на максимуме доворота почти всю
     * дугу и при этом не рыскает головой на выходе: за 15.6 градусов ошибки приказ упирается в
     * зажим {@link #MAX_STEER_OFFSET}, а ниже - гасит остаток по половине за тик.
     */
    private static final double STEER_GAIN = 5.0D;
    /** Сколько скорости заход держит сверх скорости цели. Отсюда же берётся нужда в ракете. */
    private static final double SPEED_MARGIN = 0.45D;

    /** Дальше этого упреждать бессмысленно - цель сменит курс раньше. */
    private static final double MAX_LEAD_TICKS = 40.0D;
    /** Ниже этой скорости заход не живёт вовсе: элитра сваливается. */
    private static final double MIN_CHASE_SPEED = 1.0D;
    /** Насколько голова может уйти от курса. За 78 градусов доворот физики уже не растёт. */
    private static final float MAX_STEER_OFFSET = 78.0F;
    /** Градусов тангажа на блок-за-тик недостатка скорости. */
    private static final double PITCH_PER_SPEED = 65.0D;
    private static final float MAX_DIVE = 55.0F;
    private static final float MAX_CLIMB = 32.0F;
    /**
     * Дальше этой дистанции заход не тормозит вообще, даже когда идёт быстрее нужного: нос вверх
     * это единственный тормоз в тике, и на дальней дуге он только съедает разгон. Гашение скорости
     * остаётся на прибытие, где оно и нужно - чтобы окно удара было шире одного тика.
     */
    private static final double BRAKE_DISTANCE = 25.0D;

    /** Окно передачи головы от наведения к прицелу: дальше - курс, ближе - хитбокс. */
    private static final double HANDOVER_START = 22.0D;
    public static final double HANDOVER_END = 6.0D;

    /** Запас скорости, ниже которого просится ракета. */
    private static final double THRUST_MARGIN = 0.08D;

    /** На сколько блоков вниз щупается земля и с какого запаса пикирование снова разрешено. */
    private static final double GROUND_PROBE = 32.0D;
    private static final double SAFE_CLEARANCE = 18.0D;
    private static final double FLOOR_CLEARANCE = 5.0D;

    private ElytraChase() {
    }

    /**
     * Решение наведения на один тик.
     *
     * @param aimPoint     точка, в которую аура наводит голову - уже с учётом доворота
     * @param look         тот же приказ единичным вектором
     * @param desired      истинный курс на точку встречи, без доворота
     * @param intercept    сама точка встречи
     * @param distance     до точки встречи, блоков
     * @param speed        своя скорость, блоков за тик
     * @param wantSpeed    скорость, которая нужна заходу
     * @param leadTicks    тиков упреждения, которые дало решение
     * @param headingError угол между текущим курсом и курсом на точку встречи, градусов
     */
    public record Solution(
            Vec3 aimPoint,
            Vec3 look,
            Vec3 desired,
            Vec3 intercept,
            double distance,
            double speed,
            double wantSpeed,
            double leadTicks,
            float headingError
    ) {
        /** Своим ходом до нужной скорости не хватает - просится тяга. */
        public boolean wantsThrust() {
            return this.speed + THRUST_MARGIN < this.wantSpeed;
        }
    }

    /** Решение на этот тик: куда смотреть, какой курс держать и нужна ли тяга. */
    public static Solution solve(LocalPlayer player, LivingEntity target) {
        Vec3 eye = player.getEyePosition();
        Vec3 velocity = player.getDeltaMovement();
        double speed = velocity.length();

        Vec3 targetMotion = motionOf(target);
        double targetSpeed = targetMotion.length();
        double wantSpeed = Math.max(MIN_CHASE_SPEED, targetSpeed * (1.0D + SPEED_MARGIN) + 0.1D);

        // Точку встречи ищем на той скорости, которую заход собирается держать, а не на текущей:
        // иначе на разгоне решение упреждает по вчерашней скорости и ведёт в хвост.
        double solveSpeed = Math.max(speed, wantSpeed);

        Vec3 base = target.position().add(0.0D, target.getBbHeight() * 0.75D, 0.0D);
        Vec3 relative = base.subtract(eye);
        double lead = leadTicks(relative, targetMotion, solveSpeed);
        Vec3 intercept = base.add(targetMotion.scale(lead));

        Vec3 toIntercept = intercept.subtract(eye);
        double distance = toIntercept.length();
        if (distance < 1.0E-4D) {
            return direct(player, eye, intercept, speed, wantSpeed, lead);
        }
        Vec3 desired = toIntercept.scale(1.0D / distance);

        Vec3 heading = speed > 1.0E-4D
                ? velocity.scale(1.0D / speed)
                : Vec3.directionFromRotation(player.getXRot(), player.getYRot());

        float headingYaw = yawOf(heading);
        float desiredYaw = yawOf(desired);
        float desiredPitch = pitchOf(desired);
        float yawError = Mth.wrapDegrees(desiredYaw - headingYaw);

        // Доворот. Ошибка курса умножается и зажимается: за 78 градусов от курса поперечная
        // составляющая доворота физики уже не растёт, а продольная начинает съедать скорость.
        float steeredYaw = headingYaw + Mth.clamp(
                (float) (yawError * STEER_GAIN), -MAX_STEER_OFFSET, MAX_STEER_OFFSET);

        // Газ. Плюс - нос вниз, разгон за счёт высоты; минус - нос вверх, торможение с набором.
        double speedError = wantSpeed - speed;
        float throttle = (float) Mth.clamp(speedError * PITCH_PER_SPEED, -MAX_CLIMB, MAX_DIVE);
        if (throttle > 0.0F) {
            throttle *= (float) diveAllowance(player);
        } else if (distance > BRAKE_DISTANCE) {
            throttle = 0.0F;
        }
        float steeredPitch = Mth.clamp(desiredPitch + throttle, -80.0F, 80.0F);

        // Передача головы прицелу. Ближе blendEnd наведение молчит совсем: последние блоки
        // головой правит аура, иначе доворот уводит луч с хитбокса ровно в момент удара.
        float blend = (float) Mth.clamp(
                (distance - HANDOVER_END) / (HANDOVER_START - HANDOVER_END), 0.0D, 1.0D);
        float finalYaw = desiredYaw + Mth.wrapDegrees(steeredYaw - desiredYaw) * blend;
        float finalPitch = Mth.clamp(desiredPitch + (steeredPitch - desiredPitch) * blend, -90.0F, 90.0F);

        Vec3 look = Vec3.directionFromRotation(finalPitch, finalYaw);
        Vec3 aimPoint = eye.add(look.scale(Math.max(distance, 4.0D)));

        return new Solution(
                aimPoint, look, desired, intercept, distance, speed, wantSpeed, lead, yawError);
    }

    /** Вырожденный случай - точка встречи в глазу: смотреть прямо, ничего не доворачивать. */
    private static Solution direct(
            LocalPlayer player,
            Vec3 eye,
            Vec3 intercept,
            double speed,
            double wantSpeed,
            double lead
    ) {
        Vec3 look = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        return new Solution(
                eye.add(look.scale(4.0D)), look, look, intercept, 0.0D, speed, wantSpeed, lead, 0.0F);
    }

    /**
     * Тиков до встречи: наименьший положительный корень |P + V*t| = s*t. Когда цель быстрее нас
     * и корня нет, остаётся хвостовая погоня - упреждение по прямой дальности, а разрыв закрывает
     * тяга.
     */
    private static double leadTicks(Vec3 relative, Vec3 targetMotion, double speed) {
        double a = targetMotion.lengthSqr() - speed * speed;
        double b = 2.0D * relative.dot(targetMotion);
        double c = relative.lengthSqr();

        double fallback = speed > 1.0E-4D ? Math.sqrt(c) / speed : 0.0D;
        double ticks;

        if (Math.abs(a) < 1.0E-6D) {
            ticks = Math.abs(b) < 1.0E-6D ? fallback : -c / b;
        } else {
            double discriminant = b * b - 4.0D * a * c;
            if (discriminant < 0.0D) {
                ticks = fallback;
            } else {
                double root = Math.sqrt(discriminant);
                ticks = smallestPositive((-b - root) / (2.0D * a), (-b + root) / (2.0D * a), fallback);
            }
        }

        if (!Double.isFinite(ticks) || ticks < 0.0D) {
            ticks = fallback;
        }
        return Mth.clamp(ticks, 0.0D, MAX_LEAD_TICKS);
    }

    private static double smallestPositive(double first, double second, double fallback) {
        double low = Math.min(first, second);
        double high = Math.max(first, second);
        if (low > 1.0E-4D) {
            return low;
        }
        if (high > 1.0E-4D) {
            return high;
        }
        return fallback;
    }

    /**
     * Сколько от разгонного тангажа остаётся над землёй. С восемнадцати блоков запаса пикировать
     * можно целиком, с пяти - уже нельзя вовсе. Прямой угол на цель поправка не трогает, так что
     * снижение к цели у самой земли остаётся возможным.
     */
    private static double diveAllowance(LocalPlayer player) {
        double clearance = groundClearance(player);
        return Mth.clamp((clearance - FLOOR_CLEARANCE) / (SAFE_CLEARANCE - FLOOR_CLEARANCE), 0.0D, 1.0D);
    }

    private static double groundClearance(LocalPlayer player) {
        Vec3 from = player.position();
        Vec3 to = from.subtract(0.0D, GROUND_PROBE, 0.0D);
        HitResult hit = player.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return GROUND_PROBE;
        }
        return Math.max(0.0D, from.y - hit.getLocation().y);
    }

    /** Движение цели за тик: дельта позиции, поле скорости - запасным, оно часто стухшее. */
    public static Vec3 motionOf(LivingEntity target) {
        Vec3 delta = target.position().subtract(new Vec3(target.xOld, target.yOld, target.zOld));
        return delta.lengthSqr() > 1.0E-8D ? delta : target.getDeltaMovement();
    }

    public static float yawOf(Vec3 direction) {
        return (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0D);
    }

    public static float pitchOf(Vec3 direction) {
        return (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
    }
}
