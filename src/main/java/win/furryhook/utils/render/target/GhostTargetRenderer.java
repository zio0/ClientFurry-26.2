package win.furryhook.utils.render.target;

import win.furryhook.utils.render.HurtUtil;
import win.furryhook.utils.render.Render3DUtil;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.Animation;
import win.furryhook.utils.render.particles.WorldParticleRenderer;
import win.furryhook.utils.render.particles.WorldParticleRenderer.Sprite;

import java.util.ArrayList;
import java.util.List;

public final class GhostTargetRenderer {
    private static final int SPIRAL_LIMIT = 9;
    private static final int SPIRAL_STEP = 3;
    private static final int PARTICLES_PER_SPIRAL = 20;

    private final Animation appear = new Animation(650L, Animation.Easing.EASE_OUT_EXPO);
    private final Animation inner = new Animation(850L, Animation.Easing.EASE_IN_OUT_QUAD);
    private final List<Sprite> sprites = new ArrayList<>(60);
    private final WorldParticleRenderer renderer = new WorldParticleRenderer();
    private final TargetDeathDissolve deathDissolve = new TargetDeathDissolve();

    private LivingEntity lastTarget;
    private int dissolvedTargetId = Integer.MIN_VALUE;
    private boolean animationTarget;
    private long lastFrameNanos;
    private float clock;

    public void render(LivingEntity activeTarget, float tickDelta, int color) {
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        float delta = this.lastFrameNanos == 0L
                ? 0.016F
                : Math.min(0.1F, (nowNanos - this.lastFrameNanos) / 1.0E9F);
        this.lastFrameNanos = nowNanos;

        boolean present = valid(activeTarget);
        if (present) {
            this.lastTarget = activeTarget;
            if (activeTarget.getId() == this.dissolvedTargetId) {
                this.dissolvedTargetId = Integer.MIN_VALUE;
            }
        }
        if (dead(this.lastTarget) && this.lastTarget.getId() != this.dissolvedTargetId) {
            this.deathDissolve.burst(this.sprites, this.lastTarget, color, nowMillis);
            this.dissolvedTargetId = this.lastTarget.getId();
            present = false;
        }
        updateAnimations(present);

        float appearValue = this.appear.getValue();
        float innerValue = this.inner.getValue();
        if (this.lastTarget == null || appearValue <= 0.01F || innerValue <= 0.001F) {
            if (!present && appearValue <= 0.01F) {
                this.lastTarget = null;
                this.sprites.clear();
            }
            this.deathDissolve.render(delta, nowMillis);
            return;
        }

        this.clock += delta * 1000.0F;

        Vec3 base = Render3DUtil.interpolatedPosition(this.lastTarget, tickDelta);
        float animation = this.clock / 120.0F;
        float baseAlpha = Math.min(0.85F, innerValue * 1.6F);
        int animatedColor = HurtUtil.blend(color, this.lastTarget, 1.0F);

        this.sprites.clear();
        for (int spiral = 0; spiral < SPIRAL_LIMIT; spiral += SPIRAL_STEP) {
            int spiralPhase = (int) Math.pow(spiral, 2.0D);
            for (int particle = 0; particle < PARTICLES_PER_SPIRAL; particle++) {
                float phase = animation + particle * 0.1F;
                int index = spiral / SPIRAL_STEP * PARTICLES_PER_SPIRAL + particle;
                float assembly = TargetEffectMotion.assembly(
                        innerValue,
                        index,
                        SPIRAL_LIMIT / SPIRAL_STEP * PARTICLES_PER_SPIRAL
                );
                double x = base.x
                        + 0.8F * Math.sin(phase + spiralPhase)
                        + TargetEffectMotion.scatter(index, 0, 1.75D, assembly);
                double y = base.y
                        + 0.5D
                        + 0.3F * Math.sin(animation + particle * 0.2F)
                        + 0.2F * spiral
                        + TargetEffectMotion.scatter(index, 1, 1.35D, assembly)
                        + (1.0F - assembly) * 0.35F;
                double z = base.z
                        + 0.8F * Math.cos(phase - spiralPhase)
                        + TargetEffectMotion.scatter(index, 2, 1.75D, assembly);

                float particleScale = innerValue * (0.005F + particle / 2000.0F);
                float tail = (float) Math.pow(particle / 11.0F, 1.3D);
                float alpha = Mth.clamp(baseAlpha * tail * appearValue, 0.0F, 1.0F);
                float halfSize = 25.0F * particleScale * (0.55F + assembly * 0.45F);
                if (alpha <= 0.004F || halfSize <= 0.001F) {
                    continue;
                }

                this.sprites.add(new Sprite(
                        new Vec3(x, y, z),
                        halfSize,
                        ColorUtil.multiplyAlpha(animatedColor, alpha)
                ));
            }
        }
        this.renderer.render(this.sprites, 1.0F, true);
        this.deathDissolve.render(delta, nowMillis);
    }

    public void reset() {
        this.lastTarget = null;
        this.animationTarget = false;
        this.lastFrameNanos = 0L;
        this.clock = 0.0F;
        this.dissolvedTargetId = Integer.MIN_VALUE;
        this.sprites.clear();
        this.deathDissolve.clear();
        this.appear.animate(0.0F, 0.0F, 0L, Animation.Easing.EASE_OUT_EXPO);
        this.inner.animate(0.0F, 0.0F, 0L, Animation.Easing.EASE_IN_OUT_QUAD);
    }

    private void updateAnimations(boolean present) {
        if (present == this.animationTarget) {
            return;
        }
        this.animationTarget = present;
        float target = present ? 1.0F : 0.0F;
        this.appear.animate(this.appear.getValue(), target, present ? 650L : 450L, present ? Animation.Easing.EASE_OUT_EXPO : Animation.Easing.EASE_OUT_QUAD);
        this.inner.animate(this.inner.getValue(), target, present ? 850L : 500L, present ? Animation.Easing.EASE_IN_OUT_QUAD : Animation.Easing.EASE_OUT_QUAD);
    }

    private static boolean valid(LivingEntity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved();
    }

    private static boolean dead(LivingEntity entity) {
        return entity != null
                && (entity.isDeadOrDying() || entity.deathTime > 0 || entity.getHealth() <= 0.0F);
    }
}
