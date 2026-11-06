package win.furryhook.utils.render.target;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.Animation;
import win.furryhook.utils.render.HurtUtil;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.particles.WorldParticleRenderer;
import win.furryhook.utils.render.particles.WorldParticleRenderer.Sprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Target ESP mode "Души".
 *
 * <p>Instead of a cloud of particles around the target this draws a handful of separate
 * creatures. Each one chases its own orbit point with a critically damped spring, so the motion
 * is smooth and frame-rate independent; when the aura switches target the spring goes
 * under-damped for a moment and the souls arc across in a staggered line, tails stretching with
 * their speed.
 *
 * <p>The body is not a chain of dots: the position history is resampled through a Catmull-Rom
 * spline and drawn as heavily overlapping soft sprites, so neighbouring blobs merge into one
 * shape with no visible beads. Three layers stack on top of each other — an additive HDR core
 * that burns out to white and feeds the bloom pass, a translucent body that lets the world
 * through, and a wide faint halo that dissolves the silhouette into the air.
 */
public final class SoulTargetRenderer {
    private static final float FIXED_STEP = 1.0F / 120.0F;
    private static final float MAX_FRAME = 0.1F;
    private static final float BASE_RADIUS = 0.13F;
    private static final float ASCEND_SECONDS = 0.9F;
    /** Thinnest point of the tail as a fraction of the head radius — the anti-bead floor. */
    private static final float TAIL_THICKNESS = 0.42F;
    /** Tail length cap in blocks before the trail slider scales it. */
    private static final float MAX_TAIL_LENGTH = 3.5F;

    private final List<SoulSpirit> souls = new ArrayList<>();
    private final List<Sprite> core = new ArrayList<>(512);
    private final List<Sprite> body = new ArrayList<>(512);
    private final List<Sprite> halo = new ArrayList<>(512);
    private final WorldParticleRenderer renderer = new WorldParticleRenderer();
    private final Animation appear = new Animation(600L, Animation.Easing.EASE_OUT_EXPO);
    private final float[] point = new float[3];

    private LivingEntity lastTarget;
    private int lastTargetId = Integer.MIN_VALUE;
    private boolean animationTarget;
    private long lastFrameNanos;
    private float clock;
    private float accumulator;
    private float ascend;
    private Vec3 ascendOrigin;

    public void render(AuraFeature feature, LivingEntity activeTarget, float tickDelta, int color) {
        float delta = frameDelta();
        if (feature == null) {
            reset();
            return;
        }

        boolean present = valid(activeTarget);
        if (present) {
            this.lastTarget = activeTarget;
        } else if (dead(this.lastTarget) && this.ascend <= 0.0F && this.lastTargetId != Integer.MIN_VALUE) {
            beginAscend(tickDelta);
        }

        boolean ascending = this.ascend > 0.0F;
        updateAnimations(present || ascending);
        float appearValue = this.appear.getValue();
        if (!present && !ascending && appearValue <= 0.01F) {
            softReset();
            return;
        }
        if (this.lastTarget == null && !ascending) {
            return;
        }

        int count = Mth.clamp(feature.soulCount(), 1, 8);
        float sizeScale = feature.soulSize();
        float speedScale = feature.soulSpeed();
        float trailScale = feature.soulTrail();
        boolean throughWalls = feature.soulsThroughWalls();

        Vec3 center = centerOf(present ? activeTarget : this.lastTarget, tickDelta);
        syncSouls(count, center);
        handleTargetSwitch(present ? activeTarget : null, center);
        handleImpact(present ? activeTarget : null);

        if (ascending) {
            this.ascend = Math.max(0.0F, this.ascend - delta);
        }

        advance(delta, center, speedScale, ascending);

        float fade = ascending ? this.ascend / ASCEND_SECONDS : 1.0F;
        int tinted = present
                ? HurtUtil.blend(color, activeTarget, 1.0F)
                : color;

        buildSprites(tinted, appearValue * fade, sizeScale, trailScale);

        if (!this.halo.isEmpty()) {
            this.renderer.render(this.halo, 1.1F, throughWalls, true);
        }
        if (!this.body.isEmpty()) {
            this.renderer.render(this.body, 1.3F, throughWalls, false);
        }
        if (!this.core.isEmpty()) {
            this.renderer.render(this.core, 1.9F, throughWalls, true);
        }

        if (ascending && this.ascend <= 0.0F) {
            softReset();
        }
    }

    public void reset() {
        softReset();
        this.lastFrameNanos = 0L;
        this.clock = 0.0F;
    }

    // ---------------------------------------------------------------- simulation

    private void advance(float delta, Vec3 center, float speedScale, boolean ascending) {
        this.accumulator = Math.min(0.25F, this.accumulator + delta);
        while (this.accumulator >= FIXED_STEP) {
            this.accumulator -= FIXED_STEP;
            this.clock += FIXED_STEP;
            for (SoulSpirit soul : this.souls) {
                Vec3 anchor = ascending ? ascendAnchor(soul) : orbitAnchor(soul, center, speedScale);
                soul.step(FIXED_STEP, anchor, this.clock, ascending ? 0.75F : speedScale);
            }
        }
    }

    private Vec3 orbitAnchor(SoulSpirit soul, Vec3 center, float speedScale) {
        float t = this.clock * speedScale;
        double x = Math.cos(t * soul.orbitFrequency * 2.4F + soul.phase) * soul.orbitRadius;
        double y = Math.sin(t * soul.bobFrequency * 1.7F + soul.phase * 1.7F) * soul.orbitHeight;
        double z = Math.sin(t * soul.orbitFrequency * 2.71F + soul.phase) * soul.orbitRadius;
        return center.add(x, y, z);
    }

    private Vec3 ascendAnchor(SoulSpirit soul) {
        Vec3 origin = this.ascendOrigin != null ? this.ascendOrigin : new Vec3(soul.x, soul.y, soul.z);
        float progress = 1.0F - this.ascend / ASCEND_SECONDS;
        double drift = 0.35D * Math.sin(soul.phase + progress * 2.0F);
        return origin.add(drift, 0.6D + progress * 3.2D, drift * 0.7D);
    }

    private void syncSouls(int count, Vec3 center) {
        if (this.souls.size() == count) {
            return;
        }
        while (this.souls.size() > count) {
            this.souls.remove(this.souls.size() - 1);
        }
        while (this.souls.size() < count) {
            SoulSpirit soul = new SoulSpirit(this.souls.size(), count);
            soul.placeAt(center);
            this.souls.add(soul);
        }
    }

    private void handleTargetSwitch(LivingEntity target, Vec3 center) {
        int id = target != null ? target.getId() : Integer.MIN_VALUE;
        if (id == this.lastTargetId) {
            return;
        }
        boolean firstTarget = this.lastTargetId == Integer.MIN_VALUE;
        this.lastTargetId = id;
        if (target == null) {
            return;
        }
        if (firstTarget) {
            for (SoulSpirit soul : this.souls) {
                soul.placeAt(center);
            }
            return;
        }
        for (SoulSpirit soul : this.souls) {
            Vec3 from = new Vec3(soul.x, soul.y, soul.z);
            Vec3 direction = center.subtract(from);
            if (direction.lengthSqr() < 1.0E-6D) {
                continue;
            }
            Vec3 lateral = direction.normalize().cross(new Vec3(0.0D, 1.0D, 0.0D));
            if (lateral.lengthSqr() < 1.0E-6D) {
                lateral = new Vec3(1.0D, 0.0D, 0.0D);
            }
            soul.launchTo(center, lateral.normalize());
        }
    }

    private void handleImpact(LivingEntity target) {
        if (target == null || !SoulImpulse.consume(target.getId()) || this.souls.isEmpty()) {
            return;
        }
        // The soul already closest to the target is the one that dives in.
        SoulSpirit closest = null;
        double best = Double.MAX_VALUE;
        Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        for (SoulSpirit soul : this.souls) {
            double distance = hit.distanceToSqr(soul.x, soul.y, soul.z);
            if (distance < best) {
                best = distance;
                closest = soul;
            }
        }
        if (closest == null) {
            return;
        }
        closest.impact();
        double dx = hit.x - closest.x;
        double dy = hit.y - closest.y;
        double dz = hit.z - closest.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 1.0E-4D) {
            double push = 6.5D / length;
            closest.vx += dx * push;
            closest.vy += dy * push;
            closest.vz += dz * push;
        }
    }

    // ---------------------------------------------------------------- geometry

    private void buildSprites(int color, float alphaScale, float sizeScale, float trailScale) {
        this.core.clear();
        this.body.clear();
        this.halo.clear();
        if (alphaScale <= 0.004F) {
            return;
        }

        for (SoulSpirit soul : this.souls) {
            int history = soul.historyCount();
            if (history < 2) {
                continue;
            }

            float speed = soul.speed();
            float pulse = 1.0F + 0.12F * (float) Math.sin(this.clock * 4.0F + soul.pulsePhase);
            float headRadius = BASE_RADIUS * sizeScale * pulse * (1.0F + soul.flash * 0.9F);

            float tailSamples = Mth.clamp(speed * 8.0F * trailScale, 10.0F, 90.0F);
            tailSamples = Math.min(tailSamples, history - 1.0F);
            // Cap the tail in world units too: a fast soul would otherwise stretch its samples
            // so far apart that no sane sprite count can keep them merged.
            float perSample = Math.max(1.0E-4F, speed * FIXED_STEP);
            tailSamples = Math.min(tailSamples, MAX_TAIL_LENGTH * trailScale / perSample);
            float tailLength = speed * tailSamples * FIXED_STEP;

            // The blobs must overlap along the WHOLE tail, so the spacing budget is set by the
            // thinnest part of it, not by the head — that mismatch is what turns the tail into
            // a string of beads while the head still looks solid.
            float thinnest = headRadius * TAIL_THICKNESS;
            int samples = Mth.clamp(
                    Math.round(tailLength / Math.max(0.004F, thinnest * 0.4F)) + 12, 20, 170);

            // Every extra sample adds its own light, so the per-sprite alpha has to come down
            // as the trail gets denser, otherwise a flying soul blows out to solid white.
            float density = Mth.clamp(52.0F / samples, 0.3F, 1.25F);

            for (int i = 0; i < samples; i++) {
                float u = i / (float) (samples - 1);
                soul.sample(u * tailSamples, this.point);

                // Radius keeps a floor instead of shrinking to nothing; the tail thins out
                // through alpha, which never breaks the overlap.
                float shape = TAIL_THICKNESS + (1.0F - TAIL_THICKNESS) * (float) Math.pow(1.0F - u, 0.7D);
                float radius = headRadius * shape;

                float head = (float) Math.pow(1.0F - u, 3.0D);
                float taper = (float) Math.pow(1.0F - u, 2.0D);
                int hot = ColorUtil.lerp(color, ColorUtil.WHITE,
                        Mth.clamp(0.28F + head * 0.5F + soul.flash * 0.4F, 0.0F, 1.0F));

                Vec3 position = new Vec3(this.point[0], this.point[1], this.point[2]);

                float coreAlpha = taper * alphaScale * density * (0.5F + soul.flash * 0.9F);
                if (coreAlpha > 0.004F) {
                    this.core.add(new Sprite(position, radius * 0.75F,
                            ColorUtil.multiplyAlpha(hot, Math.min(1.0F, coreAlpha))));
                }

                // The translucent pipeline multiplies by alpha a second time, so the value
                // here sits above the coverage the body should actually read as.
                float bodyAlpha = 0.3F * (1.0F - u) * alphaScale * density;
                if (bodyAlpha > 0.004F) {
                    this.body.add(new Sprite(position, radius * 1.25F,
                            ColorUtil.multiplyAlpha(color, bodyAlpha)));
                }

                float haloAlpha = 0.03F * taper * alphaScale * density;
                if (haloAlpha > 0.002F) {
                    this.halo.add(new Sprite(position, radius * 2.9F,
                            ColorUtil.multiplyAlpha(color, haloAlpha)));
                }
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private float frameDelta() {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L
                ? FIXED_STEP
                : Math.min(MAX_FRAME, (now - this.lastFrameNanos) / 1.0E9F);
        this.lastFrameNanos = now;
        return delta;
    }

    private void beginAscend(float tickDelta) {
        this.ascend = ASCEND_SECONDS;
        this.ascendOrigin = centerOf(this.lastTarget, tickDelta);
        this.lastTargetId = Integer.MIN_VALUE;
    }

    private void updateAnimations(boolean present) {
        if (present == this.animationTarget) {
            return;
        }
        this.animationTarget = present;
        this.appear.animate(
                this.appear.getValue(),
                present ? 1.0F : 0.0F,
                present ? 600L : 420L,
                present ? Animation.Easing.EASE_OUT_EXPO : Animation.Easing.EASE_OUT_QUAD
        );
    }

    private void softReset() {
        this.souls.clear();
        this.core.clear();
        this.body.clear();
        this.halo.clear();
        this.lastTarget = null;
        this.lastTargetId = Integer.MIN_VALUE;
        this.accumulator = 0.0F;
        this.ascend = 0.0F;
        this.ascendOrigin = null;
        this.animationTarget = false;
        this.appear.animate(0.0F, 0.0F, 0L, Animation.Easing.EASE_OUT_EXPO);
        SoulImpulse.clear();
    }

    private static Vec3 centerOf(LivingEntity entity, float tickDelta) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        return Render3DUtil.interpolatedPosition(entity, tickDelta)
                .add(0.0D, entity.getBbHeight() * 0.55D, 0.0D);
    }

    private static boolean valid(LivingEntity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved();
    }

    private static boolean dead(LivingEntity entity) {
        return entity != null
                && (entity.isDeadOrDying() || entity.deathTime > 0 || entity.getHealth() <= 0.0F);
    }
}
