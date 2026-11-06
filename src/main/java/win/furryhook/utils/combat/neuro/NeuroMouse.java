package win.furryhook.utils.combat.neuro;

/**
 * Сырая мышь между тиками.
 *
 * <p>Ведение мышью — это не двадцать чисел в секунду. Игрок с опросом 1000 Гц и сотней
 * кадров успевает подвинуть прицел пять-десять раз за один тик, и то, что делает движение
 * «плавным», живёт именно там: длинная равномерная серия мелких шагов против одного
 * рывка на ту же величину. Тиковая дельта у них одинаковая, а рука — совершенно разная.
 *
 * <p>Поэтому {@code MouseHandlerMixin} сливает сюда каждое движение до того, как оно
 * попадёт в игрока, а {@link win.furryhook.utils.combat.rotations.NeuroRotation} раз в
 * тик снимает накопленное и кладёт в кадр вместе с самой дельтой. Счётчик глобальный и
 * без синхронизации умышленно: и запись, и опрос идут в клиентском потоке.
 */
public final class NeuroMouse {

    private static int steps;
    private static double moved;
    private static double peak;

    private NeuroMouse() {
    }

    /** Одно движение мыши до применения к игроку. Величины — в единицах ввода, не в градусах. */
    public static void onMouseTurn(double yawDelta, double pitchDelta) {
        double magnitude = Math.hypot(yawDelta, pitchDelta);
        if (magnitude <= 0.0D) {
            return;
        }
        steps++;
        moved += magnitude;
        if (magnitude > peak) {
            peak = magnitude;
        }
    }

    public static int steps() {
        return steps;
    }

    /** Самый крупный одиночный шаг за окно, в единицах ввода. */
    public static float peak() {
        return (float) peak;
    }

    /**
     * Ровность окна, 0..1. Единица — все шаги были одинаковой длины (идеально ровное
     * ведение), ноль — всё движение пришлось на один рывок. Считается как отношение
     * среднего шага к максимальному: у равномерной серии оно единица, у серии с одним
     * выбросом падает пропорционально выбросу.
     */
    public static float evenness() {
        if (steps <= 0 || peak <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, moved / steps / peak);
    }

    /** Забирает окно и начинает новое. Зовётся ровно раз в тик, из записи. */
    public static void consume() {
        steps = 0;
        moved = 0.0D;
        peak = 0.0D;
    }
}
