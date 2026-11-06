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
import java.util.Random;

public final class CircleTargetRenderer {
    private static final float PERIOD_MILLIS = 1500.0F;
    private static final int ARMS = 4;
    private static final int PARTICLES_PER_ARM = 15;

    private final Animation appear = new Animation(650L, Animation.Easing.EASE_OUT_EXPO);
    private final Animation inner = new Animation(850L, Animation.Easing.EASE_IN_OUT_QUAD);
    private final List<Sprite> sprites = new ArrayList<>(ARMS * PARTICLES_PER_ARM);
    private final WorldParticleRenderer renderer = new WorldParticleRenderer();
    private final TargetDeathDissolve deathDissolve = new TargetDeathDissolve();

    private LivingEntity lastTarget;
    private int dissolvedTargetId = Integer.MIN_VALUE;
    private boolean animationTarget;
    private long lastFrameNanos;
    private float clock;
    private float hurtPhase;

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

        float hurt = this.lastTarget.hurtTime > 0
                ? Math.max(0.0F, (this.lastTarget.hurtTime - tickDelta) / 10.0F)
                : 0.0F;
        this.hurtPhase += hurt * delta * 500.0F;

        Vec3 base = Render3DUtil.interpolatedPosition(this.lastTarget, tickDelta);
        float height = this.lastTarget.getBbHeight();
        float cycle = (this.clock % PERIOD_MILLIS) / PERIOD_MILLIS;
        float progress = 0.5F + 0.5F * (float) Math.cos(cycle * Math.PI * 2.0D);
        double movingY = base.y + height * progress;
        float movingAngle = this.clock / 2.5F;
        float baseAlpha = Math.min(0.8F, innerValue * 1.2F);
        int animatedColor = HurtUtil.blend(color, this.lastTarget, 1.0F);

        this.sprites.clear();
        for (int arm = 0; arm < ARMS; arm++) {
            for (int particle = 0; particle < PARTICLES_PER_ARM; particle++) {
                float particleProgress = particle / 14.0F;
                int index = arm * PARTICLES_PER_ARM + particle;
                float assembly = TargetEffectMotion.assembly(
                        innerValue,
                        index,
                        ARMS * PARTICLES_PER_ARM
                );
                float worldSize = 0.5F * innerValue * (0.6F + assembly * 0.4F);
                float angle = 0.2F
                        * (movingAngle + this.hurtPhase - particle * 3.5F)
                        / 15.0F;
                float triangle = particleProgress < 0.5F
                        ? particleProgress * 2.0F
                        : (1.0F - particleProgress) * 2.0F;
                double amplitude = Math.sin(triangle * Math.PI) * 2.0D;

                Random random = new Random(particle * 12345L);
                double offsetX = (random.nextDouble() - 0.5D) * amplitude;
                double offsetY = (random.nextDouble() - 0.5D) * amplitude;
                double offsetZ = (random.nextDouble() - 0.5D) * amplitude;
                double animatedOffsetX = offsetX * assembly - offsetX;
                double animatedOffsetY = offsetY * assembly - offsetY;
                double animatedOffsetZ = offsetZ * assembly - offsetZ;

                double radius = 0.7D;
                double localX;
                double localZ;
                switch (arm) {
                    case 0 -> {
                        localX = Math.cos(angle) * radius + animatedOffsetX;
                        localZ = Math.sin(angle) * radius + animatedOffsetZ;
                    }
                    case 1 -> {
                        localX = -Math.sin(angle) * radius + animatedOffsetX;
                        localZ = Math.cos(angle) * radius + animatedOffsetZ;
                    }
                    case 2 -> {
                        localX = -Math.cos(angle) * radius + animatedOffsetX;
                        localZ = -Math.sin(angle) * radius + animatedOffsetZ;
                    }
                    default -> {
                        localX = Math.sin(angle) * radius + animatedOffsetX;
                        localZ = -Math.cos(angle) * radius + animatedOffsetZ;
                    }
                }

                float tail = (float) Math.pow(1.0F - particleProgress, 1.3D);
                float alpha = Mth.clamp(baseAlpha * tail * appearValue, 0.0F, 1.0F);
                if (alpha <= 0.004F) {
                    continue;
                }
                this.sprites.add(new Sprite(
                        new Vec3(
                                base.x + localX + TargetEffectMotion.scatter(index, 0, 1.4D, assembly),
                                movingY + animatedOffsetY
                                        + TargetEffectMotion.scatter(index, 1, 1.15D, assembly)
                                        + (1.0F - assembly) * 0.25F,
                                base.z + localZ + TargetEffectMotion.scatter(index, 2, 1.4D, assembly)
                        ),
                        worldSize * 0.5F,
                        ColorUtil.multiplyAlpha(animatedColor, alpha)
                ));
            }
        }
        this.renderer.render(this.sprites, 1.0F, true);
        this.deathDissolve.render(delta, nowMillis);
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
