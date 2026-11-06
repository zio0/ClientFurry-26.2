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

/**
 * Expensive's Attack Aura on its Funtime setting, whole and on its own.
 *
 * <p>Same decompiled sources as {@link LegitSnapRotation} — {@code TrajectoryMath},
 * {@code RotationTracker}, {@code RotationTarget}, {@code TaskScheduler}, {@code FunTimeRotation} —
 * transcribed again rather than shared, so this mode can be tuned without moving the other one.
 * There is no processor dropdown here and no FOV cone: Funtime is the only curve, and Expensive's
 * cone is 180 degrees, which rejects nothing.
 *
 * <h2>The chain</h2>
 *
 * <ol>
 *   <li><b>{@code TrajectoryMath.computeVector}</b> — eleven candidate points down the vertical
 *       centre line of the hitbox ({@code height / 10.0} apart), each kept only if it is inside reach
 *       and, unless walls are ignored, unoccluded by a COLLIDER raycast. An empty list falls back to
 *       the box point nearest the eye, and the winner is the candidate costing the least
 *       {@code hypot(yawDiff, pitchDiff)} from where the aim already is. The gaussian offset the
 *       original adds afterwards is multiplied componentwise by {@code randomValue()}, which is
 *       {@code Vec3d.ZERO} for this processor — so it never leaves zero and is not carried over.</li>
 *   <li><b>Prediction gate</b> — {@code if (n2 > 0 && !canAttack(player, 5)) return;} The module
 *       never reaches {@code scheduleRotation} until a swing is within five ticks.</li>
 *   <li><b>{@code RotationTracker}</b> — a task is scheduled with {@code expiresIn = tickCounter + 1}
 *       and so lives exactly the tick that scheduled it; the module re-schedules every tick its gate
 *       is open. Once scheduling stops, {@code RotationTarget.towards} switches to the two-argument
 *       {@code process}, which delegates to {@code process(g, g2, null, null)} — the same processor,
 *       aimed at the player's own rotation. Release needs both
 *       {@code ticksUntilReset <= tickCounter} and a residual under two degrees.</li>
 *   <li><b>{@code FunTimeRotation.process}</b>.</li>
 * </ol>
 *
 * <p>{@code ticksUntilReset} of twenty is a floor, not a tail: {@code TaskScheduler.tick} only adds
 * to {@code tickCounter} and {@code addTask} never resets it, so the counter runs monotonically from
 * the last release and the condition is permanently satisfied after the first twenty ticks. What
 * ends an engagement is the aim arriving back within two degrees of the player's own rotation.
 *
 * <h2>Adapted, not copied</h2>
 *
 * <ul>
 *   <li><b>The player's own rotation.</b> The return phase and the release both measure against
 *       {@code player.getYaw()}, which Expensive can read directly because its silent aim never
 *       writes it. Here that role belongs to {@link RotationContext#getFreeYaw()}. On release the
 *       tracker writes the spoofed yaw onto the player with {@code withFixedYaw}; this base does the
 *       opposite and restores free-look, which is {@link RotationContext#clear()}.</li>
 *   <li><b>{@code PlayerSimulation}</b> is this base's {@link TickSimulator}. Same job, its own
 *       physics.</li>
 *   <li><b>Movement correction.</b> Not duplicated: AuraFeature's "Movement Correction" already
 *       implements the NONE / FREE / TARGET / FOCUS that {@code RotationStrategy} carries as flags.</li>
 * </ul>
 */
public final class FunfickRotation implements AuraRotation {

    /** {@code RotationTarget.ticksUntilReset} for Funtime — floor on ticks since the last release. */
    private static final int TICKS_UNTIL_RESET = 20;

    /** {@code n2} for Funtime — ticks of attack lookahead required before the rotation starts. */
    private static final int PREDICT_TICKS = 5;

    /**
     * {@code AttackAuraModule.ClampedAnimator()} on Funtime. The twelve at the end is what stops the
     * CPS from being a flat metronome.
     */
    private static final int[] COOLDOWN_PATTERN = {10, 10, 10, 10, 12};

    /** {@code TrajectoryMath}: {@code box.getLengthY() / 10.0} is the candidate spacing. */
    private static final double CANDIDATE_DIVISOR = 10.0D;

    /** {@code RotationTracker.updateRotation}: residual angle under which the tracker releases. */
    private static final float RELEASE_ANGLE = 2.0F;

    private final SecureRandom secureRandom = new SecureRandom();

    private double reach = 3.0D;
    private boolean throughWalls;

    /** {@code triggerbotOnlyCriticalHit} and {@code attackauraCritsWithSpace}. */
    private boolean onlyCriticalHit;
    private boolean critsWithSpace;

    private int targetId = Integer.MIN_VALUE;
    private boolean engaged;
    private boolean settled;

    /** Whether the tracker is currently holding a rotation, target or not. */
    private boolean owning;

    /** {@code TaskScheduler.tickCounter} — monotonic; only {@code clear()} on release resets it. */
    private int tickCounter;

    /** {@code AttackAuraModule.attackTimer()} and {@code hitCounter()}, which Funtime reads directly. */
    private long lastAttackMillis = Long.MIN_VALUE / 4L;
    private int hitCounter;

    /** {@code AttackAuraModule.attackCooldown()} — the module's own hit delay, in ticks. */
    private int attackCooldown;

    /** Rewind ring {@code verticalCollisionSnapshot} reads; the check looks four to seven back. */
    private final boolean[] collisionHistory = new boolean[8];
    private int collisionCursor;
    private int collisionSamples;
    
    // Pitch randomization every 2 hits
    // Start at 0.5 (chest level, 75% height) for better initial aim
    private float targetPitchOffset = 0.5f;

    // ------------------------------------------------------------------ configuration

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

    // ------------------------------------------------------------------ state readers

    /** True while the rotation owns the aim and is driving it at the target. */
    public boolean isEngaged() {
        return this.engaged;
    }

    /** True once the aim has arrived, not merely started travelling. */
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

    /** {@code AttackAuraModule.hitCounter()} — Funtime picks its wobble pattern off this. */
    public int hitCounter() {
        return this.hitCounter;
    }

    // ------------------------------------------------------------------ tracker loop

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
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
            
            // Initialize multipoint on target switch
            float[] bodyPoints = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f};
            targetPitchOffset = bodyPoints[secureRandom.nextInt(bodyPoints.length)];
        }

        Vec3 eye = player.getEyePosition();
        AABB box = aimBox(target, targetEyePos);

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
        if (!canAttack(player, PREDICT_TICKS, box, eye)) {
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
        
        // Apply pitch offset every 2 hits - aim at different body parts (multipoint)
        if (target != null) {
            double entityHeight = target.getBbHeight();
            double entityBottom = target.getY();
            
            // targetPitchOffset: -1.0 = feet (0%), 0.0 = center (50%), 1.0 = head (100%)
            // Convert to percentage: 0.0 (feet) to 1.0 (head)
            double heightPercent = (targetPitchOffset + 1.0) * 0.5; // Maps -1..1 to 0..1
            
            // Calculate exact Y position within hitbox
            double targetY = entityBottom + (entityHeight * heightPercent);
            
            // Create multipoint aim vector
            Vec3 multipointAim = new Vec3(aim.x, targetY, aim.z);
            Vec3 multipointDiff = multipointAim.subtract(eye);
            double multipointDiffXZ = Math.sqrt(multipointDiff.x * multipointDiff.x + multipointDiff.z * multipointDiff.z);
            
            // Recalculate pitch for this specific body point
            targetPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(multipointDiff.y, multipointDiffXZ)), -90.0F, 90.0F);
        }

        // Step 3: the schedule. The task lasts one tick, so a target held inside the window is
        // re-scheduled every tick and the tracker never reaches its release branch — that branch
        // requires the scheduler to be empty, whatever the counter says.
        engaged = true;

        // Step 4: the processor.
        float[] next = funTimeRotation(currentYaw, currentPitch, targetYaw, targetPitch, critNow);

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
     * {@code process(g, g2, null, null)}. So the return runs the same processor, aimed at the
     * player's own rotation instead of the target. Funtime ignores the entity argument entirely and
     * keeps consulting {@code canCrit}, so it will still close the whole angle in one tick while
     * returning if a swing is ready.
     *
     * <p>{@code updateRotation} then decides: with {@code f = g2.angleTo(g)} against the player's own
     * rotation, it releases when {@code ticksUntilReset <= tickCounter && f < 2.0f}, and crucially
     * does <i>not</i> apply {@code g2} on the tick it releases.
     */
    private void returnPhase(boolean critNow) {
        engaged = false;
        settled = false;

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

        float[] next = funTimeRotation(currentYaw, currentPitch, freeYaw, freePitch, critNow);

        if (tickCounter >= TICKS_UNTIL_RESET
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

    // ------------------------------------------------------------------ processor

    /**
     * {@code FunTimeRotation.process}.
     *
     * <p>The per-axis clamp is {@code |component| / hypot * 180}, which only bites past a 180 degree
     * total error and is otherwise the identity — copied as it stands rather than folded away.
     *
     * <p><b>Modified: LERP removed for instant snap behavior!</b>
     * - Attack mode: Direct angle application (instant)
     * - Idle mode: Direct angle + wobble (no smoothing)
     * - Wobble still uses randomLerp for amplitude variation
     * 
     * <p><b>Enhanced with FunTime AntiCheat bypass:</b>
     * - Cap 130° max rotation speed
     * - NO smooth lerp during attack (instant snap)
     * - Randomized shake for idle state
     * - NaN protection
     * - Random pitch flick every 4 hits
     */
    private float[] funTimeRotation(float curYaw, float curPitch, float targetYaw, float targetPitch,
                                    boolean critNow) {
        float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
        float pitchDiff = targetPitch - curPitch;
        float hypot = (float) Math.hypot(Math.abs(yawDiff), Math.abs(pitchDiff));
        
        // NaN protection
        if (hypot < 0.001f || Float.isNaN(hypot) || Float.isInfinite(hypot)) {
            return new float[]{curYaw, curPitch};
        }

        // Cap 130° по прямой - для обхода античита
        float maxStepYaw = (Math.abs(yawDiff) / hypot) * 130f;
        float maxStepPitch = (Math.abs(pitchDiff) / hypot) * 130f;
        float yawStep = Mth.clamp(yawDiff, -maxStepYaw, maxStepYaw);
        float pitchStep = Mth.clamp(pitchDiff, -maxStepPitch, maxStepPitch);

        float nextYaw = curYaw;
        float nextPitch = curPitch;
        
        // Random pitch flick every 4 hits (during attack window)
        boolean shouldFlick = critNow && hitCounter > 0 && hitCounter % 4 == 0;
        long sinceAttack = System.currentTimeMillis() - lastAttackMillis;
        boolean isFlickWindow = sinceAttack < 250L;
        
        if (shouldFlick && isFlickWindow) {
            // Random pitch from -89.42 to 89.91
            nextPitch = -89.42f + secureRandom.nextFloat() * (89.91f - (-89.42f));
            nextYaw = curYaw + yawStep; // Keep yaw tracking
            return new float[]{nextYaw, Mth.clamp(nextPitch, -89.0f, 90.0f)};
        }
        
        if (critNow) {
            // attack: прямое применение без сглаживания (instant snap)
            nextYaw = curYaw + yawStep;
            nextPitch = curPitch + pitchStep;
        } else {
            // Idle state - рандомизированная тряска для обхода античита
            
            if (sinceAttack >= 535) {
                // Лимит 45° с рандомизацией
                long nowMs = System.currentTimeMillis();
                float shakeYaw = (18f + (float) Math.random() * 10f) * (float) Math.sin(nowMs / 60.0);
                float shakePitch = (6f + (float) Math.random() * 10f) * (float) Math.cos(nowMs / 60.0);
                
                nextYaw = Mth.clamp(curYaw + shakeYaw, curYaw - 45f, curYaw + 45f);
                nextPitch = Mth.clamp(curPitch + shakePitch, curPitch - 45f, curPitch + 45f);
            } else {
                // Держим натуральный взгляд сразу после удара
                float base = secureRandom.nextBoolean() ? 0.4F : 0.2F;
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

                // Прямое применение без lerp сглаживания
                nextYaw = curYaw + yawStep + yawWobble;
                nextPitch = curPitch + pitchStep + pitchWobble;
            }
        }

        // Apply GCD grid snapping for realistic mouse movement
        float[] gcdSnapped = applyGCDGrid(nextYaw, nextPitch);
        return gcdSnapped;
    }

    /** {@code RotationProcessor.randomLerp}: {@code lerp(secureRandom.nextFloat(), a, b)}. */
    private float randomLerp(float from, float to) {
        return Mth.lerp(secureRandom.nextFloat(), from, to);
    }
    
    /**
     * GCD grid snapping - quantize angles to mouse sensitivity grid
     * This simulates realistic mouse movement constrained by GCD
     */
    private float[] applyGCDGrid(float yaw, float pitch) {
        // Mouse sensitivity (default 100% = 0.5 in options)
        // GCD step is approximately 0.15 degrees per sensitivity unit
        float gcdStep = 0.15f;
        
        // Quantize to GCD grid
        float quantizedYaw = Math.round(yaw / gcdStep) * gcdStep;
        float quantizedPitch = Math.round(pitch / gcdStep) * gcdStep;
        
        return new float[]{quantizedYaw, quantizedPitch};
    }

    // ------------------------------------------------------------------ TrajectoryMath

    /**
     * {@code TrajectoryMath.computeVector(entity, reach, rotation, randomValue, throughWalls)}.
     *
     * <p>The winner is whichever candidate costs the least {@code hypot(yawDiff, pitchDiff)} from
     * where the aim already is.
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

    // ------------------------------------------------------------------ gates

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
     * {@code AttackAuraModule.canCrit(player, n)}. The processor calls this itself — it is what arms
     * the full-angle close — so it belongs to the rotation rather than to the feature that owns the
     * swing.
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
     * slow falling, in a cobweb, submerged, in lava, on a ladder or while flying, the vanilla crit is
     * unreachable and refusing to swing would just mean never swinging.
     *
     * <p>The three booleans at the end are landing rejections. {@code landingHard} and
     * {@code landingExact} throw out the tick a fall actually terminates on, and {@code steppingDown}
     * throws out a descent about to end on ground the player has been colliding with for the last
     * several ticks — a step down or a bounce, not a real fall.
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

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onAttack() {
        // attackTimer().reset() and ++hitCounter in the module. Funtime reads both: the timer gates
        // its post-hit freeze and wobble, the counter picks which of the four phase patterns runs.
        lastAttackMillis = System.currentTimeMillis();
        hitCounter++;
        
        // Multipoint - target different body parts every 2 hits
        if (hitCounter % 2 == 0) {
            // Generate specific body points for better distribution
            // Choose random from: feet (0%), knees (25%), waist (50%), chest (75%), head (100%)
            float[] bodyPoints = {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f}; // feet, knees, waist, chest, head
            targetPitchOffset = bodyPoints[secureRandom.nextInt(bodyPoints.length)];
        }
        
        // The module arms this off the outgoing swing packet, not off the attack call, and scales it
        // by 20/TPS when TPS sync is on. Without a TPS feed here it is the unscaled table.
        attackCooldown = COOLDOWN_PATTERN[Math.floorMod(hitCounter, COOLDOWN_PATTERN.length)];
    }

    @Override
    public void reset() {
        targetId = Integer.MIN_VALUE;
        engaged = false;
        settled = false;
        owning = false;
        tickCounter = 0;
        targetPitchOffset = 0.0f; // Reset multipoint offset
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
}
