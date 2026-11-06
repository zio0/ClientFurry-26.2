package win.furryhook.utils.combat.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.combat.AuraRaycast;
import win.furryhook.utils.combat.neuro.NeuroDataset;
import win.furryhook.utils.combat.neuro.NeuroLabel;
import win.furryhook.utils.combat.neuro.NeuroMouse;
import win.furryhook.utils.combat.neuro.NeuroQuery;
import win.furryhook.utils.combat.neuro.NeuroSample;

import java.util.Arrays;

/**
 * "Neuro" — a rotation with no aiming formula of its own. It is taught by the player and
 * then reproduces what it was taught.
 *
 * <p><b>Learning.</b> The rotation keeps its hands off the aim entirely and watches
 * instead: every tick it writes down the situation (angular gap to the target, how fast
 * that gap is drifting, distance, where the swing sits in the attack cooldown) together
 * with the mouse movement the player answered it with on the following tick. A run of
 * those frames from one landed hit to the next is a <i>sample</i> — one swing, complete
 * with the overshoot, the settle and the idling the player really produced. Aura keeps
 * attacking throughout, so the player fights normally and the recording is a by-product.
 *
 * <p><b>Playing back.</b> With learning off the same situation vector is built from the
 * live fight and handed to {@link NeuroDataset}, which answers with the movement from the
 * most similar frames the player ever produced. Two ways of using that answer:
 * {@code Blend} averages the k nearest frames, which generalises and grows smoother and
 * more human as the dataset fills; {@code Replay} picks one whole recorded swing and walks
 * it frame by frame, which copies a real trace exactly and only leans on the live target
 * to stay anchored.
 *
 * <p>This is closed-loop either way: the model is asked afresh every tick with the error
 * it actually has, so it corrects rather than drifting off on a dead-reckoned path.
 *
 * <p><b>Hit assist</b> is the one place where the imitation is overruled. A learned motion
 * that lands a hair off the hitbox would miss just as the player missed, so when the
 * target is in range the outgoing rotation is nudged the <i>smallest</i> amount that makes
 * the ray land — found by bisection, never more than needed. Turn it off for pure
 * imitation, misses included.
 */
public final class NeuroRotation implements AuraRotation {
    /** Shortest swing worth keeping; anything below is noise around a hit. */
    private static final int MIN_FRAMES = 3;
    /** Ticks-since-hit is capped so a long idle stretch cannot dominate the match. */
    private static final float SINCE_HIT_CAP = 40.0F;
    /** Movement wilder than this is a teleport or a screen switch, not aiming. */
    private static final float SANE_STEP = 120.0F;
    /** Ceiling on a single tick of replayed movement. */
    private static final float MAX_STEP = 90.0F;
    /** How hard a replayed frame is pulled back onto the live target. */
    private static final float REPLAY_FOLLOW = 0.35F;
    /** Plain tracking speed used while the dataset has nothing to say. */
    private static final float FALLBACK_SPEED = 0.5F;
    /** Bisection steps used to find the smallest correction that still lands. */
    private static final int ASSIST_STEPS = 6;
    /** Сколько кадров ведения без удара уже стоит того, чтобы сохранить их отдельным замахом. */
    private static final int TRACK_FLUSH_FRAMES = 60;

    private boolean learning;
    private boolean replay;
    private int neighbours = 6;
    private float variation = 0.35F;
    private boolean assist = true;
    private float assistLimit = 1.0F;
    private double reach = 3.0D;
    private boolean throughWalls;
    /** Насколько сильно подбор отталкивает кадры не из того режима боя (дуэль/свалка), 0..1. */
    private float duelBias = 0.5F;
    /** Учиться только на боях один на один. */
    private boolean duelOnly;
    /** Реплей берёт только те трассы, что кончились ударом. */
    private boolean replayHitsOnly = true;

    /** Копится по ходу замаха и уходит в подпись при сохранении. */
    private float swingDistanceSum;
    private float swingSmoothSum;
    private int swingDuelFrames;
    private int swingOpponents;

    private int targetId = Integer.MIN_VALUE;

    /** The frame being built: inputs are filled on one tick, the answer on the next. */
    private final float[] pending = new float[NeuroSample.FIELDS];
    private boolean hasPending;

    private final float[] swing = new float[NeuroSample.MAX_FRAMES * NeuroSample.FIELDS];
    private int swingFrames;

    private boolean hasHistory;
    private float previousYaw;
    private float previousPitch;
    private float previousNeedYaw;
    private float previousNeedPitch;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private int ticksSinceHit;

    private NeuroSample replaySample;
    private int replayFrame;

    /** Guards against Aura ticking the rotation twice in one game tick. */
    private int lastTick = Integer.MIN_VALUE;

    /**
     * Switches recording on or off. Leaving learning closes the swing in progress and
     * flushes the dataset to disk, so the samples survive whatever happens next.
     */
    public void setLearning(boolean learning) {
        if (this.learning == learning) {
            return;
        }
        this.learning = learning;
        if (learning) {
            discardSwing();
            clearHistory();
        } else {
            flushSwing(NeuroLabel.OUTCOME_TRACK);
            NeuroDataset.INSTANCE.saveIfDirty();
        }
    }

    public boolean isLearning() {
        return this.learning;
    }

    /** Frames banked into the swing currently being recorded. */
    public int swingFrames() {
        return this.swingFrames;
    }

    /** True for sequential whole-swing replay, false for the k-nearest-neighbour blend. */
    public void setReplay(boolean replay) {
        this.replay = replay;
    }

    /** Recorded frames averaged per tick; one copies a single trace, more smooths across several. */
    public void setNeighbours(int neighbours) {
        this.neighbours = Math.clamp(neighbours, 1, NeuroDataset.MAX_NEIGHBOURS);
    }

    /** How far the blend leans toward one randomly drawn neighbour instead of the average (0..1). */
    public void setVariation(float variation) {
        this.variation = Mth.clamp(variation, 0.0F, 1.0F);
    }

    public void setAssist(boolean assist) {
        this.assist = assist;
    }

    /** Largest share of the correction hit assist may apply (0..1); below what a hit needs, it misses. */
    public void setAssistLimit(float assistLimit) {
        this.assistLimit = Mth.clamp(assistLimit, 0.0F, 1.0F);
    }

    public void setReach(double reach) {
        this.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    /** Насколько подбор держится за кадры из такого же боя, каким идёт сейчас (0..1). */
    public void setDuelBias(float duelBias) {
        this.duelBias = Mth.clamp(duelBias, 0.0F, 1.0F);
    }

    /** Писать в датасет только размены один на один. */
    public void setDuelOnly(boolean duelOnly) {
        this.duelOnly = duelOnly;
    }

    /** Реплею выдавать только те замахи, что кончились ударом. */
    public void setReplayHitsOnly(boolean replayHitsOnly) {
        this.replayHitsOnly = replayHitsOnly;
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        // Aura ticks rotations from the game tick and, under "Таргет" movement correction,
        // from the input event as well. A second pass would record a frame the player never
        // moved through and step the replay cursor twice, so only the first one counts.
        if (player.tickCount == this.lastTick) {
            return;
        }
        this.lastTick = player.tickCount;

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            // Смена цели закрывает замах, а не выбрасывает его: ведение до размена — это
            // тоже то, как игрок водит, и раньше оно уходило в мусор целиком.
            if (this.learning) {
                flushSwing(NeuroLabel.OUTCOME_LOST);
            } else {
                discardSwing();
            }
            clearHistory();
        }

        Vec3 eye = player.getEyePosition(1.0F);
        float[] need = rotationTo(eye, targetEyePos);
        float distance = (float) eye.distanceTo(targetEyePos);

        if (this.learning) {
            record(player, target, need, distance);
        } else {
            play(player, target, need, distance);
        }
    }

    @Override
    public void onAttack() {
        if (this.learning) {
            // Aura attacks before it ticks the rotation, so the swing closes here and the
            // next frame recorded already belongs to the following swing.
            flushSwing(NeuroLabel.OUTCOME_HIT);
            this.hasPending = false;
        } else {
            this.replaySample = null;
            this.replayFrame = 0;
        }
        this.ticksSinceHit = 0;
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        // Цель кончилась или бой прекратился — набранное ведение сохраняется подписанным,
        // а не выбрасывается: раньше всё, что не дошло до удара, пропадало целиком.
        if (this.learning) {
            flushSwing(NeuroLabel.OUTCOME_LOST);
            this.hasPending = false;
        } else {
            discardSwing();
        }
        clearHistory();
    }

    /**
     * Watches the player aim without touching the rotation. Each tick closes the previous
     * frame with the movement that actually happened and opens a new one on the state the
     * player is now looking at.
     */
    private void record(LocalPlayer player, LivingEntity target, float[] need, float distance) {
        // Switching into learning mid-fight leaves the aim wherever the previous rotation
        // spoofed it to, and nothing else hands it back while a target is still up. Ease it
        // onto the player's real view first: what gets recorded has to be their own mouse.
        if (RotationContext.isActive()) {
            RotationContext.disengage();
            this.hasPending = false;
            NeuroMouse.consume();
            clearHistory();
            return;
        }

        // In a menu the mouse is not aiming, so there is nothing here worth learning.
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.screen() != null) {
            this.hasPending = false;
            NeuroMouse.consume();
            clearHistory();
            return;
        }

        // Подтиковая мышь снимается ровно раз за тик и ровно здесь: окно закрывается,
        // даже если кадр в итоге забракован — иначе следующий кадр унёс бы чужую руку.
        int mouseSteps = NeuroMouse.steps();
        float mousePeak = NeuroMouse.peak();
        float evenness = NeuroMouse.evenness();
        NeuroMouse.consume();

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        float movedYaw = 0.0F;
        float movedPitch = 0.0F;
        if (this.hasHistory) {
            movedYaw = Mth.wrapDegrees(yaw - this.previousYaw);
            movedPitch = pitch - this.previousPitch;
        }
        boolean sane = Math.abs(movedYaw) <= SANE_STEP && Math.abs(movedPitch) <= SANE_STEP;

        if (this.hasPending && this.hasHistory && sane) {
            this.pending[NeuroSample.DELTA_YAW] = movedYaw;
            this.pending[NeuroSample.DELTA_PITCH] = movedPitch;
            appendFrame();
        }

        this.pending[NeuroSample.ERR_YAW] = Mth.wrapDegrees(need[0] - yaw);
        this.pending[NeuroSample.ERR_PITCH] = need[1] - pitch;
        this.pending[NeuroSample.PREV_DELTA_YAW] = sane ? movedYaw : 0.0F;
        this.pending[NeuroSample.PREV_DELTA_PITCH] = sane ? movedPitch : 0.0F;
        this.pending[NeuroSample.TARGET_YAW_SPEED] = this.hasHistory ? Mth.wrapDegrees(need[0] - this.previousNeedYaw) : 0.0F;
        this.pending[NeuroSample.TARGET_PITCH_SPEED] = this.hasHistory ? need[1] - this.previousNeedPitch : 0.0F;
        this.pending[NeuroSample.DISTANCE] = distance;
        this.pending[NeuroSample.SINCE_HIT] = Math.min(this.ticksSinceHit, SINCE_HIT_CAP);

        // Вторая половина кадра — то, чем один и тот же угловой разрыв отличается от
        // другого: как быстро уходит цель, сколькими движениями рука это отработала,
        // насколько ровно, насколько дёрнуто и был ли это вообще размен один на один.
        int opponents = countOpponents(player);
        boolean duel = opponents == 1;
        this.pending[NeuroSample.TARGET_SPEED] = (float) targetSpeed(target);
        this.pending[NeuroSample.MOUSE_STEPS] = mouseSteps;
        this.pending[NeuroSample.MOUSE_PEAK] = mousePeak;
        this.pending[NeuroSample.SMOOTHNESS] = evenness;
        this.pending[NeuroSample.JERK] = sane
                ? (float) Math.hypot(movedYaw - this.lastDeltaYaw, movedPitch - this.lastDeltaPitch)
                : 0.0F;
        this.pending[NeuroSample.DUEL] = duel ? 1.0F : 0.0F;
        this.hasPending = true;

        this.lastDeltaYaw = sane ? movedYaw : 0.0F;
        this.lastDeltaPitch = sane ? movedPitch : 0.0F;

        this.swingDistanceSum += distance;
        this.swingSmoothSum += evenness;
        if (duel) {
            this.swingDuelFrames++;
        }
        this.swingOpponents = Math.max(this.swingOpponents, opponents);

        // Длинное ведение без единого удара — тоже материал, и терять его нельзя: у игрока,
        // который полминуты держал цель на дистанции, ровно там и живёт вся плавность.
        if (this.swingFrames >= TRACK_FLUSH_FRAMES) {
            flushSwing(NeuroLabel.OUTCOME_TRACK);
        }

        rememberState(yaw, pitch, need);
    }

    /** Собственная скорость цели, блоков за тик — по дельте позиции, не по стухшему полю. */
    private static double targetSpeed(LivingEntity target) {
        if (target == null) {
            return 0.0D;
        }
        return target.position().distanceTo(new Vec3(target.xOld, target.yOld, target.zOld));
    }

    /**
     * Сколько живых чужих игроков рядом. Ровно один — это размен один на один, и такие
     * кадры подписываются отдельно: в свалке прицел перекидывают, и ведение там другое.
     */
    private static int countOpponents(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0;
        }
        int count = 0;
        for (Player other : mc.level.players()) {
            if (other == player || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (other.distanceToSqr(player) > NeuroLabel.DUEL_RADIUS * NeuroLabel.DUEL_RADIUS) {
                continue;
            }
            if (FriendManager.INSTANCE.isFriend(other.getGameProfile().name())) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** Asks the dataset what the player would have done here, and does that. */
    private void play(LocalPlayer player, LivingEntity target, float[] need, float distance) {
        // Окно мыши закрывается и здесь: иначе включённое посреди боя обучение получит
        // первым кадром весь мусор, накопленный за время воспроизведения.
        NeuroMouse.consume();

        float yaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float pitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        float errYaw = Mth.wrapDegrees(need[0] - yaw);
        float errPitch = need[1] - pitch;

        NeuroQuery query = new NeuroQuery(
                errYaw,
                errPitch,
                this.lastDeltaYaw,
                this.lastDeltaPitch,
                this.hasHistory ? Mth.wrapDegrees(need[0] - this.previousNeedYaw) : 0.0F,
                this.hasHistory ? need[1] - this.previousNeedPitch : 0.0F,
                distance,
                Math.min(this.ticksSinceHit, SINCE_HIT_CAP),
                (float) targetSpeed(target),
                countOpponents(player) == 1
        );

        float[] step = this.replay
                ? replayStep(query)
                : NeuroDataset.INSTANCE.predict(query, this.neighbours, this.variation, this.duelBias);
        if (step == null) {
            // Untrained, or an angle nothing in the dataset covers: track plainly rather
            // than stand still, so the aura still works while the player is filling it up.
            step = new float[]{errYaw * FALLBACK_SPEED, errPitch * FALLBACK_SPEED};
        }

        float newYaw = yaw + Mth.clamp(step[0], -MAX_STEP, MAX_STEP);
        float newPitch = Mth.clamp(pitch + Mth.clamp(step[1], -MAX_STEP, MAX_STEP), -90.0F, 90.0F);

        if (this.assist && distance <= this.reach) {
            float[] corrected = correct(player, target, newYaw, newPitch);
            newYaw = corrected[0];
            newPitch = corrected[1];
        }

        RotationContext.setRotation(newYaw, newPitch);

        // Read back what actually left: setRotation quantises to the mouse-sensitivity GCD,
        // so the applied step is what the next tick must treat as its previous movement.
        float appliedYaw = RotationContext.getServerYaw();
        float appliedPitch = RotationContext.getServerPitch();
        this.lastDeltaYaw = Mth.wrapDegrees(appliedYaw - yaw);
        this.lastDeltaPitch = appliedPitch - pitch;

        rememberState(appliedYaw, appliedPitch, need);
    }

    /**
     * One frame of a recorded swing, corrected for the difference between the target the
     * swing was recorded against and the one in front of the player now.
     */
    private float[] replayStep(NeuroQuery query) {
        if (this.replaySample == null || this.replayFrame >= this.replaySample.frames()) {
            this.replaySample = NeuroDataset.INSTANCE.nearestSample(query, this.duelBias, this.replayHitsOnly);
            this.replayFrame = 0;
            if (this.replaySample == null) {
                return null;
            }
        }

        float recordedErrYaw = this.replaySample.get(this.replayFrame, NeuroSample.ERR_YAW);
        float recordedErrPitch = this.replaySample.get(this.replayFrame, NeuroSample.ERR_PITCH);
        float yaw = this.replaySample.get(this.replayFrame, NeuroSample.DELTA_YAW)
                + Mth.wrapDegrees(query.errYaw() - recordedErrYaw) * REPLAY_FOLLOW;
        float pitch = this.replaySample.get(this.replayFrame, NeuroSample.DELTA_PITCH)
                + (query.errPitch() - recordedErrPitch) * REPLAY_FOLLOW;

        this.replayFrame++;
        return new float[]{yaw, pitch};
    }

    /**
     * Smallest nudge toward the hitbox that makes the ray land. The learned rotation and
     * the ideal one bound a segment whose far end is inside the (convex) silhouette, so
     * the blends that hit form one interval ending at 1 — bisection finds where it starts,
     * and {@link #assistLimit} caps how much of it may actually be spent.
     */
    private float[] correct(LocalPlayer player, LivingEntity target, float yaw, float pitch) {
        float[] unchanged = {yaw, pitch};
        if (hits(player, target, yaw, pitch)) {
            return unchanged;
        }

        Vec3 point = AuraRaycast.findAimPoint(player, target, target.position(), this.reach, this.throughWalls, Vec3.ZERO);
        if (point == null) {
            return unchanged;
        }

        float[] ideal = rotationTo(player.getEyePosition(1.0F), point);
        float gapYaw = Mth.wrapDegrees(ideal[0] - yaw);
        float gapPitch = ideal[1] - pitch;
        if (!hits(player, target, yaw + gapYaw, Mth.clamp(pitch + gapPitch, -90.0F, 90.0F))) {
            return unchanged;
        }

        float miss = 0.0F;
        float hit = 1.0F;
        for (int step = 0; step < ASSIST_STEPS; step++) {
            float middle = (miss + hit) * 0.5F;
            if (hits(player, target, yaw + gapYaw * middle, Mth.clamp(pitch + gapPitch * middle, -90.0F, 90.0F))) {
                hit = middle;
            } else {
                miss = middle;
            }
        }

        float blend = Math.min(hit, this.assistLimit);
        return new float[]{
                yaw + gapYaw * blend,
                Mth.clamp(pitch + gapPitch * blend, -90.0F, 90.0F)
        };
    }

    private boolean hits(LocalPlayer player, LivingEntity target, float yaw, float pitch) {
        return AuraRaycast.rotationIntersectsTarget(player, target, yaw, pitch, this.reach, 0.0D, 0.0D, this.throughWalls);
    }

    private void appendFrame() {
        if (this.swingFrames >= NeuroSample.MAX_FRAMES) {
            // A swing this long never reached a hit; drop the oldest frame so the buffer
            // keeps sliding instead of freezing on stale state.
            System.arraycopy(this.swing, NeuroSample.FIELDS, this.swing, 0, (NeuroSample.MAX_FRAMES - 1) * NeuroSample.FIELDS);
            this.swingFrames = NeuroSample.MAX_FRAMES - 1;
        }
        System.arraycopy(this.pending, 0, this.swing, this.swingFrames * NeuroSample.FIELDS, NeuroSample.FIELDS);
        this.swingFrames++;
    }

    /**
     * Банкует набранный замах как сэмпл вместе с подписью. Зовётся на ударе (там подпись —
     * {@link NeuroLabel#OUTCOME_HIT}), на потере цели и на длинном ведении без размена.
     */
    private void flushSwing(String outcome) {
        if (this.swingFrames >= MIN_FRAMES && !(this.duelOnly && this.swingDuelFrames * 2 < this.swingFrames)) {
            int values = this.swingFrames * NeuroSample.FIELDS;
            NeuroDataset.INSTANCE.add(new NeuroSample(
                    Arrays.copyOf(this.swing, values),
                    this.swingFrames,
                    System.currentTimeMillis(),
                    buildLabel(outcome)
            ));
        }
        clearSwingStats();
    }

    /**
     * Подпись к замаху. Дуэлью он считается, если больше половины его кадров сняты один на
     * один — короткое вмешательство третьего посреди размена не переклеивает ярлык.
     */
    private NeuroLabel buildLabel(String outcome) {
        int frames = Math.max(1, this.swingFrames);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        int ping = 0;
        if (mc.getConnection() != null && player != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) {
                ping = info.getLatency();
            }
        }
        String weapon = player == null
                ? ""
                : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath();

        return new NeuroLabel(
                outcome,
                this.swingDuelFrames * 2 >= this.swingFrames,
                this.swingOpponents,
                this.swingSmoothSum / frames,
                this.swingDistanceSum / frames,
                ping,
                weapon
        );
    }

    private void discardSwing() {
        clearSwingStats();
        this.hasPending = false;
    }

    private void clearSwingStats() {
        this.swingFrames = 0;
        this.swingDistanceSum = 0.0F;
        this.swingSmoothSum = 0.0F;
        this.swingDuelFrames = 0;
        this.swingOpponents = 0;
    }

    private void clearHistory() {
        this.hasHistory = false;
        this.lastDeltaYaw = 0.0F;
        this.lastDeltaPitch = 0.0F;
        this.ticksSinceHit = 0;
        this.replaySample = null;
        this.replayFrame = 0;
    }

    private void rememberState(float yaw, float pitch, float[] need) {
        this.previousYaw = yaw;
        this.previousPitch = pitch;
        this.previousNeedYaw = need[0];
        this.previousNeedPitch = need[1];
        this.hasHistory = true;
        this.ticksSinceHit++;
    }

    private static float[] rotationTo(Vec3 eye, Vec3 point) {
        Vec3 diff = point.subtract(eye);
        double horizontal = Math.hypot(diff.x, diff.z);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }
}
