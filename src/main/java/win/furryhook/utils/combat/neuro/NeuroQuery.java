package win.furryhook.utils.combat.neuro;

import net.minecraft.util.Mth;

/**
 * Ситуация, о которой спрашивают модель прицела, и метрика, решающая, какие записанные
 * кадры считаются «игрок здесь уже был».
 *
 * <p>Поля живут в разных единицах (градусы, блоки, тики), поэтому каждая разница делится
 * на масштаб — сколько её считается одной единицей непохожести, — и взвешивается тем,
 * насколько она должна тянуть подбор. Угловой разрыв до цели главенствует; дистанция,
 * фаза кулдауна и скорость цели только разводят похожие кадры между собой.
 */
public record NeuroQuery(
        float errYaw,
        float errPitch,
        float prevDeltaYaw,
        float prevDeltaPitch,
        float targetYawSpeed,
        float targetPitchSpeed,
        float distance,
        float sinceHit,
        float targetSpeed,
        boolean duel
) {
    private static final float ERR_YAW_SCALE = 8.0F;
    private static final float ERR_PITCH_SCALE = 6.0F;
    private static final float DELTA_SCALE = 4.0F;
    private static final float SPEED_SCALE = 5.0F;
    private static final float DISTANCE_SCALE = 1.5F;
    private static final float SINCE_HIT_SCALE = 6.0F;
    private static final float TARGET_SPEED_SCALE = 0.25F;

    private static final float ERR_WEIGHT = 1.0F;
    private static final float DELTA_WEIGHT = 0.6F;
    private static final float SPEED_WEIGHT = 0.5F;
    private static final float DISTANCE_WEIGHT = 0.35F;
    private static final float SINCE_HIT_WEIGHT = 0.3F;
    private static final float TARGET_SPEED_WEIGHT = 0.3F;

    /** Пенальти кадру, снятому не в том режиме боя, что запрошен: дуэль против свалки. */
    private static final float DUEL_MISMATCH = 0.35F;

    /**
     * Взвешенный квадрат расстояния до кадра, начинающегося со смещения {@code offset} в
     * {@code data}. Участвуют только входные поля — записанное движение это ответ, а не
     * часть вопроса.
     *
     * @param duelBias насколько сильно кадры «не из того боя» отталкиваются, 0..1
     */
    public float distanceSqr(float[] data, int offset, float duelBias) {
        // Разрыв по yaw уже свёрнут в [-180, 180], так что два кадра по разные стороны от
        // склейки лежат в двух градусах друг от друга, а не в 358.
        float yawGap = Mth.wrapDegrees(this.errYaw - data[offset + NeuroSample.ERR_YAW]);

        float total = term(yawGap, ERR_YAW_SCALE, ERR_WEIGHT)
                + term(this.errPitch - data[offset + NeuroSample.ERR_PITCH], ERR_PITCH_SCALE, ERR_WEIGHT)
                + term(this.prevDeltaYaw - data[offset + NeuroSample.PREV_DELTA_YAW], DELTA_SCALE, DELTA_WEIGHT)
                + term(this.prevDeltaPitch - data[offset + NeuroSample.PREV_DELTA_PITCH], DELTA_SCALE, DELTA_WEIGHT)
                + term(this.targetYawSpeed - data[offset + NeuroSample.TARGET_YAW_SPEED], SPEED_SCALE, SPEED_WEIGHT)
                + term(this.targetPitchSpeed - data[offset + NeuroSample.TARGET_PITCH_SPEED], SPEED_SCALE, SPEED_WEIGHT)
                + term(this.distance - data[offset + NeuroSample.DISTANCE], DISTANCE_SCALE, DISTANCE_WEIGHT)
                + term(this.sinceHit - data[offset + NeuroSample.SINCE_HIT], SINCE_HIT_SCALE, SINCE_HIT_WEIGHT)
                + term(this.targetSpeed - data[offset + NeuroSample.TARGET_SPEED], TARGET_SPEED_SCALE, TARGET_SPEED_WEIGHT);

        // Кадр из свалки в дуэли ведёт себя иначе — там прицел постоянно перекидывают.
        // Не отбрасывается, только отодвигается: на пустом датасете лучше похожий чужой
        // кадр, чем ничего.
        if (duelBias > 0.0F) {
            boolean frameDuel = data[offset + NeuroSample.DUEL] > 0.5F;
            if (frameDuel != this.duel) {
                total += DUEL_MISMATCH * duelBias;
            }
        }
        return total;
    }

    private static float term(float difference, float scale, float weight) {
        float normalized = difference / scale;
        return normalized * normalized * weight;
    }
}
