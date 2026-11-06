package win.furryhook.utils.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Порт {@code tech.javelin.base.util.math.MultipointUtils.getNearestPoint}, один в один.
 *
 * <p>Javelin не берёт зажатую по осям проекцию глаза на коробку - он перебирает сетку точек
 * внутри хитбокса с шагом 0.1 и выбирает ближайшую к глазу. На выпуклой коробке результат тот же,
 * что у проекции, но с одной разницей, которая и важна: сетка ходит по узлам шага, поэтому точка
 * всегда лежит строго внутри, а не на грани, и дистанция до неё чуть больше - именно на этой
 * дистанции javelin и режет реч.
 */
public final class MultipointUtil {

    private static final double DEFAULT_STEP = 0.1D;

    private MultipointUtil() {
    }

    public static Vec3 getNearestPoint(Entity entity, double expand) {
        return getNearestPoint(entity, expand, DEFAULT_STEP);
    }

    /**
     * Тот же результат, что у перебора javelin, но без перебора.
     *
     * <p>Квадрат расстояния до узла - это сумма трёх слагаемых, каждое из которых зависит только от
     * своей оси, а сетки по осям независимы. Значит минимум суммы достигается на поосевых минимумах,
     * и каждую ось можно решить отдельно: зажать координату глаза в диапазон узлов и округлить к
     * ближайшему. Перебор давал 200-650 объектов {@link Vec3} за вызов, а вызывается это из
     * {@code canAttack} каждый тик - именно такая аллокационная молотилка и держит GC в постоянной
     * работе на ровном месте.
     */
    public static Vec3 getNearestPoint(Entity entity, double expand, double step) {
        LocalPlayer player = Minecraft.getInstance().player;
        AABB box = entity.getBoundingBox();
        if (player == null) {
            return box.getCenter();
        }

        Vec3 eye = player.getEyePosition();
        double x = nearestNode(box.minX + expand, box.maxX - expand, step, eye.x);
        double y = nearestNode(box.minY + expand, box.maxY - expand, step, eye.y);
        double z = nearestNode(box.minZ + expand, box.maxZ - expand, step, eye.z);

        // Javelin возвращает null, если сетка пуста (expand съел коробку). Здесь этого допускать
        // нельзя - вызывающий код меряет дистанцию сразу, поэтому падаем на центр коробки.
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return box.getCenter();
        }
        return new Vec3(x, y, z);
    }

    /**
     * Ближайший к {@code target} узел сетки {@code min, min+step, ...} внутри {@code [min, max]}.
     * На точной середине между двумя узлами берётся нижний - так же, как перебор javelin, который
     * заменяет кандидата только по строгому "меньше". {@code NaN}, если ни одного узла нет.
     */
    private static double nearestNode(double min, double max, double step, double target) {
        if (max < min || step <= 0.0D) {
            return Double.NaN;
        }
        long lastIndex = (long) Math.floor((max - min) / step + 1.0E-9D);
        // Округление вниз на ровной середине: -1e-9 сдвигает точку .5 к нижнему узлу.
        long index = Math.round((target - min) / step - 1.0E-9D);
        index = Math.max(0L, Math.min(lastIndex, index));
        return min + index * step;
    }
}
