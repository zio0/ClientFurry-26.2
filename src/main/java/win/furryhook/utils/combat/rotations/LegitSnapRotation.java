package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.movement.AutoJumpFeature;
import win.furryhook.utils.math.TickSimulator;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Expensive's AttackAura rotation, ported off the decompiled sources: {@code TrajectoryMath},
 * {@code RotationTracker}, {@code RotationTarget}, {@code RotationStrategy}, {@code FunTimeRotation}
 * and {@code FunTimeRotation2}.
 *
 * <h2>The chain</h2>
 *
 * <ol>
 *   <li><b>{@code TrajectoryMath.computeVector}</b> — eleven candidate points down the vertical
 *       centre line of the hitbox ({@code height / 10.0} apart), each kept only if it is inside reach
 *       and, unless walls are ignored, not occluded by a COLLIDER raycast. Empty list falls back to
 *       the box point nearest the eye, and the winner is the candidate with the smallest
 *       {@code hypot(yawDiff, pitchDiff)} against the current rotation. The accumulated gaussian
 *       offset is componentwise-multiplied by {@code randomValue()}, which is {@code Vec3d.ZERO} for
 *       both Funtime processors — so it is permanently zero and no randomisation is applied.</li>
 *   <li><b>Prediction gate</b> — {@code if (n2 > 0 && !canAttack(player, n2)) return;} The module
 *       never reaches {@code scheduleRotation} until a swing is inside the window.</li>
 *   <li><b>{@code RotationTracker}</b> — the task lives one tick, so a target held in the window is
 *       re-scheduled every tick. Once scheduling stops, {@code RotationTarget.towards} switches to
 *       the two-argument {@code process}, easing back to the player's own rotation, and the tracker
 *       only lets go once {@code ticksUntilReset <= tickCounter} <i>and</i> the residual angle is
 *       under two degrees.</li>
 *   <li><b>Processor</b> — {@code FunTimeRotation} / {@code FunTimeRotation2} / {@code SnapRotation}.</li>
 * </ol>
 *
 * <pre>
 *            ticksUntilReset (n3)   predict (n2)   processor
 *  FUNTIME          20                   5         FunTimeRotation
 *  SMOOTH            5                   0         FunTimeRotation2
 *  GRIM              2                   4         SnapRotation
 * </pre>
 *
 * <p>{@code ticksUntilReset} is a floor on the engagement, not a tail. {@code TaskScheduler.tick}
 * only ever adds to {@code tickCounter} and {@code addTask} does not touch it, so the counter runs
 * monotonically from the last release; {@code n3 <= tickCounter} is therefore satisfied permanently
 * after the first {@code n3} ticks of activity, and what actually ends an engagement is the aim
 * arriving back within two degrees of the player's own rotation. The number only stops a brief
 * engagement from releasing on the tick it started: Funtime has to run twenty ticks first, Grim two.
 *
 * <p>What keeps a mode latched, then, is the prediction window rather than the reset count. A task
 * is scheduled with {@code expiresIn = tickCounter + 1}, so it is valid for exactly the tick that
 * scheduled it; the module re-schedules every tick its gate is open, and Funtime's gate opens five
 * ticks before a swing while Grim's opens four.
 *
 * <h2>Adapted, not copied</h2>
 *
 * <ul>
 *   <li><b>FOV.</b> Expensive's target cone is 180 degrees — off. The ring is kept because
 *       AuraFeature draws it and gates the swing on {@link #isEngaged()}; it sits in front of the
 *       pipeline as the engage condition. Put the FOV slider at its maximum and the cone is dropped
 *       entirely, which is the setting that reproduces Expensive exactly.</li>
 *   <li><b>{@code canCrit(player, 0)}.</b> Both Funtime processors snap the full remaining angle in
 *       one tick when it is true. Ported whole, simulation included, because every processor asks
 *       it directly — the aura that owns the swing is not consulted.</li>
 *   <li><b>The player's own rotation.</b> The return phase and the release both measure against
 *       {@code player.getYaw()}, which Expensive can read directly because its silent aim never
 *       writes it. Here that role belongs to {@link RotationContext#getFreeYaw()}. On release the
 *       tracker writes the spoofed yaw onto the player with {@code withFixedYaw}; this base does the
 *       opposite and restores free-look, which is {@link RotationContext#clear()}.</li>
 *   <li><b>Movement correction.</b> Not duplicated: AuraFeature's "Movement Correction" already
 *       implements the NONE / FREE / TARGET / FOCUS that {@code RotationStrategy} carries as flags.</li>
 * </ul>
 */
public final class LegitSnapRotation implements AuraRotation {

    /** Which of Expensive's three processors runs, with its tracker numbers from the module. */
    public enum Mode {
        FUNTIME(20, 5, new int[]{10, 10, 10, 10, 12}),
        SMOOTH(5, 0, new int[]{10, 10, 10, 13}),
        GRIM(2, 4, new int[]{10});

        /** {@code RotationTarget.ticksUntilReset} — floor on ticks since the last release. */
        final int ticksUntilReset;
        /** {@code n2} — ticks of attack lookahead required before the rotation starts at all. */
        final int predictTicks;
        /**
         * {@code AttackAuraModule.ClampedAnimator()} — the hit delay in ticks, cycled by hit count.
         * The odd entry at the end of each pattern is what stops the CPS from being a flat metronome;
         * Grim has no table there and always uses ten.
         */
        final int[] cooldownPattern;

        Mode(int ticksUntilReset, int predictTicks, int[] cooldownPattern) {
            this.ticksUntilReset = ticksUntilReset;
            this.predictTicks = predictTicks;
            this.cooldownPattern = cooldownPattern;
        }

        int cooldownFor(int hitCounter) {
            return cooldownPattern[Math.floorMod(hitCounter, cooldownPattern.length)];
        }

        public static Mode of(String name) {
            if (name == null) {
                return FUNTIME;
            }
            return switch (name) {
                case "Smooth" -> SMOOTH;
                case "Grim" -> GRIM;
                default -> FUNTIME;
            };
        }
    }

    /** {@code TrajectoryMath}: {@code box.getLengthY() / 10.0} is the candidate spacing. */
    private static final double CANDIDATE_DIVISOR = 10.0D;

    /** {@code RotationTracker.updateRotation}: residual angle under which the tracker releases. */
    private static final float RELEASE_ANGLE = 2.0F;

    private static final float DEFAULT_FOV_ANGLE = 25.0F;

    /** At the FOV setting's maximum the cone is dropped entirely, matching Expensive's 180. */
    private static final float FOV_DISABLED_AT = 90.0F;

    /** Ticks the engage is held after a swing, so a combo does not release between hits. */
    private static final int ATTACK_HOLD_TICKS = 4;

    private final SecureRandom secureRandom = new SecureRandom();

    private Mode mode = Mode.FUNTIME;
    private float fovAngle = DEFAULT_FOV_ANGLE;
    private boolean fovDisabled;
    private double reach = 3.0D;
    private boolean throughWalls;

    private int targetId = Integer.MIN_VALUE;
    private boolean engaged;
    private boolean settled;
    private boolean targetInFov;
    private int attackHold;

    /** Whether the tracker is currently holding a rotation, target or not. */
    private boolean owning;

    /** {@code TaskScheduler.tickCounter} — monotonic; only {@code clear()} on release resets it. */
    private int tickCounter;

    /** {@code AttackAuraModule.attackTimer()} and {@code hitCounter()}, which Funtime reads directly. */
    private long lastAttackMillis = Long.MIN_VALUE / 4L;
    private int hitCounter;

    /** {@code triggerbotOnlyCriticalHit} and {@code attackauraCritsWithSpace}. */
    private boolean onlyCriticalHit;
    private boolean critsWithSpace;

    /** {@code AttackAuraModule.attackCooldown()} — the module's own hit delay, in ticks. */
    private int attackCooldown;

    /** Rewind ring {@code verticalCollisionSnapshot} reads; the check looks four to seven back. */
    private final boolean[] collisionHistory = new boolean[8];
    private int collisionCursor;
    private int collisionSamples;

    // ------------------------------------------------------------------ configuration

    /**
     * Half-cone (degrees) inside which the rotation engages; driven by AuraFeature's FOV setting.
     *
     * <p>At the slider's maximum the cone is treated as absent rather than as ninety degrees. That
     * is the setting that gives Expensive's behaviour exactly: its own cone is 180 and therefore
     * never rejects anything, so the aura engages on whatever the target selector handed it,
     * wherever the player happens to be looking.
     */
    public void setFovAngle(float fovAngle) {
        this.fovAngle = Math.max(1.0F, fovAngle);
        this.fovDisabled = fovAngle >= FOV_DISABLED_AT;
    }

    public void setMode(Mode mode) {
        if (mode != null && mode != this.mode) {
            this.mode = mode;
            this.tickCounter = 0;
        }
    }

    public void setMode(String mode) {
        setMode(Mode.of(mode));
    }

    /** Attack reach candidate points are filtered against, matching the module's max distance. */
    public void setReach(double reach) {
        this.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    /** {@code triggerbotOnlyCriticalHit} — without it {@code canCrit} is just the cooldown check. */
    public void setOnlyCriticalHit(boolean onlyCriticalHit) {
        this.onlyCriticalHit = onlyCriticalHit;
    }

    /** {@code attackauraCritsWithSpace} — lets a standing, non-jumping tick count as ready. */
    public void setCritsWithSpace(boolean critsWithSpace) {
        this.critsWithSpace = critsWithSpace;
    }

    /** AuraFeature's hit counter, if it would rather drive Funtime's wobble pattern itself. */
    public void setHitCounter(int hitCounter) {
        this.hitCounter = hitCounter;
    }

    // ------------------------------------------------------------------ state readers

    /** True while the rotation owns the aim. AuraFeature refuses to swing when this is false. */
    public boolean isEngaged() {
        return this.engaged;
    }

    /** True while the target's hitbox is inside the drawn FOV ring, engaged or not. */
    public boolean isTargetInFov() {
        return this.targetInFov;
    }

    /** True once the aim has actually arrived, not merely started travelling. */
    public boolean isOnTarget() {
        return this.engaged && this.settled;
    }

    /**
     * True while the tracker still owns a rotation it has not released.
     *
     * <p>Expensive's tracker outlives the target: {@code getCurrentStrategy()} falls back to the
     * sticky {@code rotationTarget}, so {@code updateRotation} keeps running the return curve after
     * the module has stopped scheduling and stops only at the release. AuraFeature returns out of
     * {@code updateRotation} the moment the target is null, so it has to ask.
     */
    public boolean isReturning() {
        return this.owning && RotationContext.isActive();
    }

    public Mode mode() {
        return this.mode;
    }

    public int hitCounter() {
        return this.hitCounter;
    }

    // ------------------------------------------------------------------ tracker loop

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (attackHold > 0) {
            attackHold--;
        }
        // The module decrements its hit delay at the head of the POST stage, before anything reads it.
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (player != null) {
            recordCollision(player);
        }

        if (player == null) {
            reset();
            return;
        }

        // canCrit runs a movement simulation, so it is asked once a tick and handed down. Funtime
        // guards it with target() != null; the return phase therefore never sees it armed.
        boolean critNow = target != null && target.isAlive() && canCrit(player, 0);

        if (target == null || !target.isAlive()) {
            returnPhase(critNow);
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.engaged = false;
            this.settled = false;
            this.tickCounter = 0;
        }

        Vec3 eye = player.getEyePosition();
        AABB box = aimBox(target, targetEyePos);

        // The cone is measured against what the player is actually looking at: the free-look angle
        // while something is spoofing, the player's own rotation when nothing is. Measured against
        // the spoofed angle it would latch on the first tick and never let go.
        float viewYaw = RotationContext.isActive() ? RotationContext.getFreeYaw() : player.getYRot();
        float viewPitch = RotationContext.isActive() ? RotationContext.getFreePitch() : player.getXRot();
        targetInFov = fovDisabled
                || angleBetween(eye, box.getCenter(), viewYaw, viewPitch) <= fovAngle;

        if (!targetInFov && attackHold <= 0) {
            returnPhase(critNow);
            return;
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        // Step 1: TrajectoryMath.computeVector.
        Vec3 aim = computeVector(player, box, eye, currentYaw, currentPitch);
        if (aim == null) {
            returnPhase(critNow);
            return;
        }

        // Step 2: the prediction gate. Failing it, the module returns before scheduleRotation and
        // the tracker is left to run its own counter down.
        if (mode.predictTicks > 0 && !canAttack(player, mode.predictTicks, box, eye)) {
            returnPhase(critNow);
            return;
        }

        Vec3 diff = aim.subtract(eye);
        double diffXZ = Math.hypot(diff.x, diff.z);
        if (diffXZ < 1.0E-5D) {
            returnPhase(critNow);
            return;
        }

        float targetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float targetPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, diffXZ)), -90.0F, 90.0F);

        // Step 3: the schedule. The task lasts one tick, so a target held inside the window is
        // re-scheduled every tick and the tracker never reaches its release branch — that branch
        // requires the scheduler to be empty, whatever the counter says.
        engaged = true;

        // Step 4: the processor. Grim asks the one-tick-lookahead question the other two do not:
        // canAttack(player, 1), not canCrit(player, 0).
        float[] next = switch (mode) {
            case FUNTIME -> funTimeRotation(currentYaw, currentPitch, targetYaw, targetPitch, critNow);
            case SMOOTH -> funTimeRotation2(currentYaw, currentPitch, targetYaw, targetPitch, critNow);
            case GRIM -> snapRotation(currentYaw, currentPitch, targetYaw, targetPitch,
                    critNow || canCrit(player, 1));
        };

        settled = angleTo(next[0], next[1], targetYaw, targetPitch) < RELEASE_ANGLE;

        RotationContext.setRotation(next[0], Mth.clamp(next[1], -90.0F, 90.0F));
        owning = true;

        // taskscheduler.tick(1) runs at the tail of every update that did not release, this one
        // included, so the counter measures ticks since the last release rather than quiet ticks.
        tickCounter++;
    }

    /**
     * The tracker with no task on the scheduler — {@code towards(current, true)}.
     *
     * <p>{@code RotationProcessor.process(g, g2)} is not a separate curve: it delegates straight to
     * {@code process(g, g2, null, null)}. So the return runs <i>the same processor</i>, aimed at the
     * player's own rotation instead of the target, with a null entity.
     *
     * <p>That null is load-bearing for Grim. {@code SnapRotation} reads
     * {@code entity != null && canAttack(player, 1)}, so its 1.0 branch is unreachable here and the
     * return can only ever ease at 0.4 to 0.7 — or push away at -0.2 inside 50ms of a hit. The two
     * Funtime processors ignore the entity entirely and keep consulting {@code canCrit}, so they
     * will still close the whole angle in one tick while returning if a swing is ready.
     *
     * <p>{@code updateRotation} then decides: with {@code f = g2.angleTo(g)} against the player's
     * own rotation, it releases when {@code ticksUntilReset <= tickCounter && f < 2.0f}, and
     * crucially does <i>not</i> apply {@code g2} on the tick it releases.
     */
    private void returnPhase(boolean critNow) {
        engaged = false;
        settled = false;
        targetInFov = false;

        if (!RotationContext.isActive()) {
            tickCounter = 0;
            owning = false;
            return;
        }

        float currentYaw = RotationContext.getServerYaw();
        float currentPitch = RotationContext.getServerPitch();

        // The player's own rotation. While this base is spoofing, that is the free-look angle —
        // Expensive can read player.getYaw() directly only because its silent aim never writes it.
        float freeYaw = RotationContext.getFreeYaw();
        float freePitch = RotationContext.getFreePitch();

        float[] next = switch (mode) {
            case FUNTIME -> funTimeRotation(currentYaw, currentPitch, freeYaw, freePitch, critNow);
            case SMOOTH -> funTimeRotation2(currentYaw, currentPitch, freeYaw, freePitch, critNow);
            case GRIM -> snapRotation(currentYaw, currentPitch, freeYaw, freePitch, false);
        };

        if (tickCounter >= mode.ticksUntilReset
                && angleTo(next[0], next[1], freeYaw, freePitch) < RELEASE_ANGLE) {
            RotationContext.clear();
            tickCounter = 0;
            owning = false;
            return;
        }

        RotationContext.setRotation(next[0], Mth.clamp(next[1], -90.0F, 90.0F));
        owning = true;
        tickCounter++;
    }

    // ------------------------------------------------------------------ processors

    /**
     * {@code FunTimeRotation.process}.
     *
     * <p>The per-axis clamp is {@code |component| / hypot * 180}, which only bites past a 180 degree
     * total error and is otherwise the identity — copied as it stands rather than folded away.
     *
     * <p>The two timers are the whole character of the mode. Under 500ms since the last hit the lerp
     * factor is drawn from {@code [-0.2, 0.0]} and clamps to zero for most of that draw, so the aim
     * simply stops after a swing. Under 1000ms an oscillator adds six to twelve degrees of yaw and
     * two to four of pitch on top, cycling through four phase patterns by hit count. And when the
     * swing is ready it abandons all of it and closes the full angle in one tick.
     */
    private float[] funTimeRotation(float curYaw, float curPitch, float targetYaw, float targetPitch,
                                    boolean critNow) {
        float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
        float pitchDiff = targetPitch - curPitch;
        float hypot = (float) Math.hypot(Math.abs(yawDiff), Math.abs(pitchDiff));
        if (hypot < 1.0E-5F) {
            return new float[]{curYaw, curPitch};
        }

        float yawLimit = Math.abs(yawDiff / hypot) * 180.0F;
        float pitchLimit = Math.abs(pitchDiff / hypot) * 180.0F;
        float yawStep = Mth.clamp(yawDiff, -yawLimit, yawLimit);
        float pitchStep = Mth.clamp(pitchDiff, -pitchLimit, pitchLimit);

        if (critNow) {
            // canCrit: g.add(new Rotation2f(f7, f8)) — the entire remaining angle, this tick.
            return new float[]{curYaw + yawStep, curPitch + pitchStep};
        }

        float base = secureRandom.nextBoolean() ? 0.4F : 0.2F;
        long sinceAttack = System.currentTimeMillis() - lastAttackMillis;
        float low = sinceAttack < 500L ? -0.2F : base;

        float phaseA = (float) (System.currentTimeMillis() % 2000L) / 75.0F;
        float phaseB = (float) (System.currentTimeMillis() % 1000L) / 75.0F;

        float waveYaw;
        float wavePitch;
        switch (Math.floorMod(hitCounter, 4)) {
            case 0 -> {
                waveYaw = (float) Math.cos(phaseA);
                wavePitch = (float) Math.sin(phaseB);
            }
            case 1 -> {
                waveYaw = (float) Math.sin(phaseA);
                wavePitch = (float) Math.cos(phaseB);
            }
            case 2 -> {
                waveYaw = (float) Math.sin(phaseA);
                wavePitch = (float) -Math.cos(phaseB);
            }
            default -> {
                waveYaw = (float) -Math.cos(phaseA);
                wavePitch = (float) Math.sin(phaseB);
            }
        }

        float yawWobble = sinceAttack < 1000L ? randomLerp(6.0F, 12.0F) * waveYaw : 0.0F;
        float pitchWobble = sinceAttack < 1000L ? randomLerp(2.0F, 4.0F) * wavePitch : 0.0F;

        float yaw = Mth.lerp(Mth.clamp(randomLerp(low, low + 0.2F), 0.0F, 1.0F), curYaw, curYaw + yawStep) + yawWobble;
        float pitch = Mth.lerp(Mth.clamp(randomLerp(low, low + 0.2F), 0.0F, 1.0F), curPitch, curPitch + pitchStep) + pitchWobble;

        return new float[]{yaw, pitch};
    }

    /**
     * {@code FunTimeRotation2.process}.
     *
     * <p>Hard per-tick ceilings this time — 60 degrees of yaw and 40 of pitch, distributed by each
     * axis' share of the total error — plus a {@code sin(millis / 40.0)} yaw oscillation of two to
     * five degrees that switches off the moment the swing is ready.
     *
     * <p>The lerp factors are {@code 0.6 + rand * 0.6} and {@code 0.5 + rand * 0.5}. The yaw one runs
     * past 1.0, so the turn overshoots the point by up to twenty percent and settles back — that is
     * in the original and it is deliberate, not a rounding artefact.
     */
    private float[] funTimeRotation2(float curYaw, float curPitch, float targetYaw, float targetPitch,
                                     boolean critNow) {
        float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
        float pitchDiff = targetPitch - curPitch;
        float hypot = (float) Math.hypot(Math.abs(yawDiff), Math.abs(pitchDiff));
        if (hypot < 1.0E-5F) {
            return new float[]{curYaw, curPitch};
        }

        float yawLimit = Math.abs(yawDiff / hypot) * 60.0F;
        float pitchLimit = Math.abs(pitchDiff / hypot) * 40.0F;

        float oscillation = critNow
                ? 0.0F
                : (float) Math.ceil((double) randomLerp(2.0F, 5.0F) * Math.sin((double) System.currentTimeMillis() / 40.0D));

        float yawStep = Mth.clamp(yawDiff, -yawLimit, yawLimit) + oscillation;
        float pitchStep = Mth.clamp(pitchDiff, -pitchLimit, pitchLimit);

        float yawFactor = 0.6F + ThreadLocalRandom.current().nextFloat() * 0.6F;
        float pitchFactor = 0.5F + ThreadLocalRandom.current().nextFloat() * 0.5F;

        return new float[]{
                Mth.lerp(yawFactor, curYaw, curYaw + yawStep),
                Mth.lerp(pitchFactor, curPitch, curPitch + pitchStep)
        };
    }

    /**
     * {@code SnapRotation} — the Grim processor. No oscillator and no wobble; the whole mode is
     * three factors.
     *
     * <ul>
     *   <li><b>1.0</b> when {@code entity != null && canAttack(player, 1)} — the full remaining
     *       angle, this tick. That is the snap, and it is armed one tick ahead of the swing rather
     *       than on it.</li>
     *   <li><b>0.4 to 0.7</b> otherwise, drawn fresh per axis, so yaw and pitch never close at the
     *       same rate.</li>
     *   <li><b>-0.2</b> inside 50ms of the last hit. Negative, and unlike {@code FunTimeRotation}
     *       nothing clamps it to zero here — the aim is pushed a fifth of the delta <i>away</i> from
     *       the target for the tick after a swing lands, then closes again.</li>
     * </ul>
     *
     * <p>CFR folded the yaw factor into a variable it then failed to assign on the {@code canAttack}
     * branch, which cannot compile; the two factors are the same expression drawn twice, one per
     * axis, and that is what is written here.
     */
    private float[] snapRotation(float curYaw, float curPitch, float targetYaw, float targetPitch,
                                 boolean snapNow) {
        float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
        float pitchDiff = targetPitch - curPitch;
        float hypot = (float) Math.hypot(Math.abs(yawDiff), Math.abs(pitchDiff));
        if (hypot < 1.0E-5F) {
            return new float[]{curYaw, curPitch};
        }

        float yawLimit = Math.abs(yawDiff / hypot) * 180.0F;
        float pitchLimit = Math.abs(pitchDiff / hypot) * 180.0F;

        boolean settledEnough = System.currentTimeMillis() - lastAttackMillis >= 50L;
        float yawFactor = snapNow ? 1.0F : (settledEnough ? getRandom(0.4F, 0.7F) : -0.2F);
        float pitchFactor = snapNow ? 1.0F : (settledEnough ? getRandom(0.4F, 0.7F) : -0.2F);

        return new float[]{
                Mth.lerp(yawFactor, curYaw, curYaw + Mth.clamp(yawDiff, -yawLimit, yawLimit)),
                Mth.lerp(pitchFactor, curPitch, curPitch + Mth.clamp(pitchDiff, -pitchLimit, pitchLimit))
        };
    }

    /** {@code MathUtil.getRandom(a, b)}. */
    private float getRandom(float from, float to) {
        return from + ThreadLocalRandom.current().nextFloat() * (to - from);
    }

    /** {@code RotationProcessor.randomLerp}: {@code lerp(secureRandom.nextFloat(), a, b)}. */
    private float randomLerp(float from, float to) {
        return Mth.lerp(secureRandom.nextFloat(), from, to);
    }

    // ------------------------------------------------------------------ TrajectoryMath

    /**
     * {@code TrajectoryMath.computeVector(entity, reach, rotation, randomValue, throughWalls)}.
     *
     * <p>Candidates run down the vertical centre line of the hitbox at {@code height / 10.0}
     * spacing. Each survives only if it is inside reach and, unless walls are ignored, unoccluded.
     * An empty list falls back to the box point nearest the eye, and the winner is whichever
     * candidate costs the least {@code hypot(yawDiff, pitchDiff)} from where the aim already is.
     *
     * <p>The gaussian offset the original adds afterwards is multiplied componentwise by
     * {@code randomValue()}, and both Funtime processors return {@code Vec3d.ZERO}, so it never
     * moves off zero and is not carried over.
     */
    private Vec3 computeVector(LocalPlayer player, AABB box, Vec3 eye, float curYaw, float curPitch) {
        List<Vec3> candidates = generateCandidatePoints(player, box, eye);

        Vec3 best = null;
        double bestCost = Double.MAX_VALUE;
        for (Vec3 candidate : candidates) {
            double cost = rotationCost(eye, candidate, curYaw, curPitch);
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    /** {@code TrajectoryMath.generateCandidatePoints}. */
    private List<Vec3> generateCandidatePoints(LocalPlayer player, AABB box, Vec3 eye) {
        double step = box.getYsize() / CANDIDATE_DIVISOR;
        Vec3 center = box.getCenter();
        List<Vec3> points = new ArrayList<>();

        if (step > 1.0E-6D) {
            for (double y = box.minY; y <= box.maxY; y += step) {
                Vec3 candidate = new Vec3(center.x, y, center.z);
                if (isReachable(player, eye, candidate)) {
                    points.add(candidate);
                }
            }
        }

        if (points.isEmpty()) {
            Vec3 closest = getClosestVec(eye, box);
            if (isReachable(player, eye, closest)) {
                points.add(closest);
            }
        }

        return points;
    }

    /** {@code TrajectoryMath.hasValidPoint} — the same scan, asked as a yes/no. */
    public boolean hasValidPoint(LocalPlayer player, LivingEntity target, Vec3 targetEyePos) {
        if (player == null || target == null) {
            return false;
        }
        return !generateCandidatePoints(player, aimBox(target, targetEyePos), player.getEyePosition()).isEmpty();
    }

    /** {@code TrajectoryMath.getClosestVec}. */
    private Vec3 getClosestVec(Vec3 from, AABB box) {
        return new Vec3(
                Mth.clamp(from.x, box.minX, box.maxX),
                Mth.clamp(from.y, box.minY, box.maxY),
                Mth.clamp(from.z, box.minZ, box.maxZ)
        );
    }

    /** Inside reach, and unoccluded unless walls are ignored. COLLIDER context, as in the original. */
    private boolean isReachable(LocalPlayer player, Vec3 eye, Vec3 point) {
        if (eye.distanceTo(point) > reach) {
            return false;
        }
        if (throughWalls) {
            return true;
        }
        BlockHitResult hit = player.level().clip(new ClipContext(
                eye,
                point,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    /** The comparator {@code computeVector} sorts candidates by. */
    private double rotationCost(Vec3 eye, Vec3 point, float curYaw, float curPitch) {
        Vec3 diff = point.subtract(eye);
        double diffXZ = Math.hypot(diff.x, diff.z);
        if (diffXZ < 1.0E-5D) {
            return Double.MAX_VALUE;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, diffXZ)), -90.0F, 90.0F);

        return Math.hypot(Mth.wrapDegrees(yaw - curYaw), pitch - curPitch);
    }

    // ------------------------------------------------------------------ gates and helpers

    /**
     * {@code AttackAuraModule.canAttack(player, n)} reduced to the half of it the rotation owns: a
     * swing ready within {@code n} ticks, and a reachable point on the target. Crit, screen and item
     * gating stay in AuraFeature and are not duplicated.
     */
    private boolean canAttack(LocalPlayer player, int ticks, AABB box, Vec3 eye) {
        if (box.distanceToSqr(eye) > reach * reach) {
            return false;
        }
        for (int i = 0; i <= ticks; i++) {
            if (canCrit(player, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code AttackAuraModule.canCrit(player, n)}. Every processor calls this itself — it is what
     * arms Funtime's and Snap's full-angle close — so it belongs to the rotation rather than to the
     * feature that owns the swing.
     *
     * <pre>
     * ready = getAttackCooldownProgress(n + 0.5f) &gt; 0.9f &amp;&amp; (attackCooldown - n) &lt;= 0
     * if (!ready)              return false;
     * if (!onlyCriticalHit)    return true;
     * jumping = sim(n).jumping || AutoJump is on
     * if (critsWithSpace &amp;&amp; sim(n).onGround &amp;&amp; !jumping) return true;
     * return isCriticalWindow(player, n);
     * </pre>
     */
    public boolean canCrit(LocalPlayer player, int ticks) {
        boolean ready = player.getAttackStrengthScale(ticks + 0.5F) > 0.9F && attackCooldown - ticks <= 0;
        if (!ready) {
            return false;
        }
        if (!onlyCriticalHit) {
            return true;
        }

        Level level = player.level();
        TickSimulator.SimState state = TickSimulator.simulateLocalPlayer(player, ticks, level);

        boolean jumping = state.jumpHeld
                || FeatureManager.INSTANCE.getEnabled(AutoJumpFeature.class) != null;

        if (critsWithSpace && state.onGround && !jumping) {
            return true;
        }
        return isCriticalWindow(player, ticks, level, state);
    }

    /**
     * The second half of the crit gate. The early {@code return true}s are not "this is a crit" —
     * they are "criticals do not apply here, so stop gating the hit": under levitation, blindness or
     * slow falling, in a cobweb, submerged, in lava, on a ladder or while flying, the vanilla crit
     * is unreachable and refusing to swing would just mean never swinging.
     *
     * <p>The three booleans at the end are landing rejections. {@code bl8} and {@code bl9} throw out
     * the tick a fall actually terminates on, and {@code bl10} throws out a descent that is about to
     * end on ground the player has been colliding with for the last several ticks — a step down or a
     * bounce, not a real fall.
     */
    private boolean isCriticalWindow(LocalPlayer player, int ticks, Level level, TickSimulator.SimState state) {
        if (hasEffect(player, MobEffects.LEVITATION, ticks)
                || hasEffect(player, MobEffects.BLINDNESS, ticks)
                || hasEffect(player, MobEffects.SLOW_FALLING, ticks)) {
            return true;
        }

        if (state.inCobweb
                || (state.submergedInWater && state.inWater)
                || state.inLava
                || state.climbing
                || player.getAbilities().flying) {
            return true;
        }

        if (player.getMainHandItem().is(Items.MACE)) {
            return MaceItem.canSmashAttack(player);
        }

        boolean groundNext = TickSimulator.simulateLocalPlayer(player, ticks + 1, level).onGround;
        boolean groundAfter = TickSimulator.simulateLocalPlayer(player, ticks + 2, level).onGround;

        boolean landingHard = state.fallDistance > 1.0F && groundNext;
        boolean landingExact = state.fallDistance == (float) state.motion.y && groundNext;
        boolean steppingDown = state.motion.y < -0.3D
                && groundAfter
                && (verticalCollisionSnapshot(player, 4)
                || verticalCollisionSnapshot(player, 5)
                || verticalCollisionSnapshot(player, 6)
                || verticalCollisionSnapshot(player, 7));

        return !state.onGround
                && state.fallDistance > 0.0F
                && !steppingDown
                && !landingExact
                && !landingHard;
    }

    /**
     * {@code PlayerSimulation.hasStatusEffect} — the effect has to outlast the simulation, or a
     * potion about to run out would be treated as still active at the tick being predicted.
     */
    private boolean hasEffect(LocalPlayer player, Holder<MobEffect> effect, int ticks) {
        MobEffectInstance instance = player.getEffect(effect);
        return instance != null && instance.getDuration() >= ticks;
    }

    /**
     * {@code EntityRendererUtil.getSnapshot(player, n).verticalCollision}, which reads a rewind
     * history rather than a simulation — so it is fed from the live player once a tick here.
     */
    private boolean verticalCollisionSnapshot(LocalPlayer player, int ticksAgo) {
        if (ticksAgo <= 0 || ticksAgo > collisionHistory.length || ticksAgo > collisionSamples) {
            return player.verticalCollision;
        }
        int index = Math.floorMod(collisionCursor - ticksAgo, collisionHistory.length);
        return collisionHistory[index];
    }

    /** Pushes this tick's collision flag into the rewind ring the snapshot check reads. */
    private void recordCollision(LocalPlayer player) {
        collisionHistory[collisionCursor] = player.verticalCollision;
        collisionCursor = (collisionCursor + 1) % collisionHistory.length;
        if (collisionSamples < collisionHistory.length) {
            collisionSamples++;
        }
    }

    @Override
    public void onAttack() {
        // attackTimer().reset() and ++hitCounter in the module. Funtime reads both: the timer gates
        // its post-hit freeze and wobble, the counter picks which of the four phase patterns runs.
        lastAttackMillis = System.currentTimeMillis();
        hitCounter++;
        attackHold = ATTACK_HOLD_TICKS;
        // The module arms this off the outgoing swing packet, not off the attack call, and scales it
        // by 20/TPS when TPS sync is on. Without a TPS feed here it is the unscaled table.
        attackCooldown = mode.cooldownFor(hitCounter);
    }

    @Override
    public void reset() {
        targetId = Integer.MIN_VALUE;
        engaged = false;
        settled = false;
        targetInFov = false;
        attackHold = 0;
        tickCounter = 0;
        owning = false;
    }

    /**
     * Hitbox to aim into, shifted onto the predicted eye position when something upstream
     * (ElytraTarget / ElytraResolver) is leading the target, so prediction still applies here.
     */
    private AABB aimBox(LivingEntity target, Vec3 targetEyePos) {
        AABB box = target.getBoundingBox();
        if (targetEyePos != null) {
            Vec3 offset = targetEyePos.subtract(target.getEyePosition());
            if (offset.lengthSqr() > 1.0E-6D) {
                box = box.move(offset);
            }
        }
        return box;
    }

    /** {@code Rotation2f.angleTo}. */
    private float angleTo(float yawA, float pitchA, float yawB, float pitchB) {
        return (float) Math.hypot(Mth.wrapDegrees(yawA - yawB), pitchA - pitchB);
    }

    /** True 3D angle in degrees between a look direction and the direction to {@code point}. */
    private double angleBetween(Vec3 eye, Vec3 point, float yaw, float pitch) {
        Vec3 toPoint = point.subtract(eye);
        double length = toPoint.length();
        if (length < 1.0E-6D) {
            return 0.0D;
        }

        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double dot = Mth.clamp(look.dot(toPoint) / length, -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }
}
