package win.furryhook.utils.render.world;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import win.furryhook.feature.impl.visual.HandsTestFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.post.KawaseBlur;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.post.PostPipelines;

public final class HandsTestRenderer {
    private static final int MASK_UNIFORM_SIZE = new com.mojang.blaze3d.buffers.Std140SizeCalculator()
            .putVec2()
            .putVec2()
            .get();
    private static final int TRAIL_UNIFORM_SIZE = new com.mojang.blaze3d.buffers.Std140SizeCalculator()
            .putVec2()
            .putFloat().putFloat().putFloat().putFloat().putFloat().putFloat()
            .putFloat().putFloat().putFloat().putFloat().putFloat().putFloat()
            .putVec2()
            .putVec4()
            .get();
    private static final int FIRE_UNIFORM_SIZE = new com.mojang.blaze3d.buffers.Std140SizeCalculator()
            .putVec2()
            .putFloat().putFloat().putFloat().putFloat().putFloat().putFloat()
            .putVec4()
            .get();

    private final GpuBuffer maskUniforms = PostFx.createUniforms("Blade HandsTest Mask UBO", MASK_UNIFORM_SIZE);
    private final GpuBuffer trailUniforms = PostFx.createUniforms("Blade HandsTest Trail UBO", TRAIL_UNIFORM_SIZE);
    private final GpuBuffer fireUniforms = PostFx.createUniforms("Blade HandsTest Fire UBO", FIRE_UNIFORM_SIZE);
    private final KawaseBlur blur = new KawaseBlur("furryhook-handstest-blur");

    private TextureTarget beforeTarget;
    private TextureTarget afterTarget;
    private TextureTarget maskRawTarget;
    private TextureTarget maskTarget;
    private TextureTarget trailA;
    private TextureTarget trailB;
    private boolean trailUsesA;

    private long lastNanos;
    private float smoothDt = 1.0F / 60.0F;
    private float smoothActivity;
    private float slash;
    private float slashDir = 1.0F;
    private boolean wasSwinging;
    private float prevSwingProgress;
    private final long startNanos = System.nanoTime();
    private float prevYaw;
    private float prevPitch;
    private boolean hasPrevCam;

    public void render(HandsTestFeature feature, Runnable handDraw) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (!validMainTarget(mainTarget)) {
            handDraw.run();
            return;
        }

        ensureTargets(mainTarget.width, mainTarget.height);
        if (!resourcesReady()) {
            handDraw.run();
            return;
        }

        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                mainTarget.getColorTexture(), this.beforeTarget.getColorTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height
        );
        encoder.copyTextureToTexture(
                mainTarget.getDepthTexture(), this.beforeTarget.getDepthTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height
        );

        handDraw.run();

        encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                mainTarget.getColorTexture(), this.afterTarget.getColorTexture(),
                0, 0, 0, 0, 0, mainTarget.width, mainTarget.height
        );

        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        writeMaskUniforms(mainTarget.width, mainTarget.height);
        renderMask(mainTarget, sampler);

        float time = PostFx.shaderTime();
        State state = advanceState(minecraft, mainTarget.width, mainTarget.height);

        writeTrailUniforms(feature, mainTarget.width, mainTarget.height, time, state);
        renderTrail(sampler);

        int levels = Math.clamp((int) Math.ceil(feature.trailBlur.getValue().floatValue() * 3.0F), 1, 4);
        GpuTextureView blurred = this.blur.run(
                (this.trailUsesA ? this.trailB : this.trailA).getColorTextureView(),
                mainTarget.width, mainTarget.height, levels,
                Math.max(0.5F, feature.trailBlur.getValue().floatValue() * 1.4F),
                sampler
        );

        writeFireUniforms(feature, mainTarget.width, mainTarget.height, time, state.activity);
        renderFire(mainTarget, blurred, sampler);

        this.trailUsesA = !this.trailUsesA;
    }

    private record State(float activity, float slash, float slashDir, float swingHand, float camShiftX, float camShiftY) {
    }

    private State advanceState(Minecraft minecraft, int width, int height) {
        long now = System.nanoTime();
        float rawDt = this.lastNanos > 0L ? (now - this.lastNanos) / 1.0E9F : 1.0F / 60.0F;
        this.lastNanos = now;
        rawDt = Mth.clamp(rawDt <= 0.0F || rawDt > 0.05F ? this.smoothDt : rawDt, 1.0F / 144.0F, 1.0F / 30.0F);
        this.smoothDt += (rawDt - this.smoothDt) * 0.1F;
        float dt = this.smoothDt;

        LocalPlayer player = minecraft.player;
        boolean swinging = player != null && player.swinging;
        float swingProgress = player != null ? player.getAttackAnim(1.0F) : 0.0F;
        boolean restartedSwing = swinging && (swingProgress + 0.08F < this.prevSwingProgress || (!this.wasSwinging && swingProgress > 0.02F));
        if (restartedSwing) {
            this.slash = 1.0F;
            this.slashDir = -this.slashDir;
        }
        this.wasSwinging = swinging;
        this.prevSwingProgress = swingProgress;
        if (swinging) {
            this.slash = Math.max(this.slash, 1.0F - Math.min(1.0F, swingProgress) * 0.72F);
        }
        this.slash = Math.max(0.0F, this.slash - dt * 2.2F);

        float motion = player != null ? (float) Math.min(1.0, player.getDeltaMovement().horizontalDistance() * 2.2) : 0.0F;
        float swingActivity = swinging ? 1.0F - Math.min(1.0F, swingProgress) : 0.0F;
        float useActivity = player != null && player.isUsingItem()
                ? 0.55F + Math.min(0.45F, player.getTicksUsingItem() * 0.06F)
                : 0.0F;
        float activityTarget = Math.max(Math.max(swingActivity, useActivity), motion);
        this.smoothActivity += (activityTarget - this.smoothActivity) * (1.0F - (float) Math.exp(-dt * 6.0));

        float swingHand = 0.0F;
        if (player != null) {
            InteractionHand activeHand = swinging ? InteractionHand.MAIN_HAND
                    : (player.isUsingItem() ? player.getUsedItemHand() : InteractionHand.MAIN_HAND);
            HumanoidArm mainArm = player.getMainArm();
            if (activeHand == InteractionHand.MAIN_HAND) {
                swingHand = mainArm == HumanoidArm.LEFT ? -1.0F : 1.0F;
            } else {
                swingHand = mainArm == HumanoidArm.LEFT ? 1.0F : -1.0F;
            }
        }

        float camShiftX = 0.0F;
        float camShiftY = 0.0F;
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera != null) {
            float yaw = camera.yRot();
            float pitch = camera.xRot();
            if (this.hasPrevCam) {
                float dYaw = wrapDegrees(yaw - this.prevYaw);
                float dPitch = pitch - this.prevPitch;
                float fovDeg = minecraft.options.fov().get();
                float tanHalf = (float) Math.tan(Math.toRadians(Math.max(1.0F, fovDeg)) * 0.5F);
                float aspect = height > 0 ? (float) width / (float) height : 1.0F;
                camShiftX = (float) Math.toRadians(dYaw) / (2.0F * tanHalf * aspect);
                camShiftY = -(float) Math.toRadians(dPitch) / (2.0F * tanHalf);
                camShiftX = Mth.clamp(camShiftX, -0.25F, 0.25F);
                camShiftY = Mth.clamp(camShiftY, -0.25F, 0.25F);
            }
            this.prevYaw = yaw;
            this.prevPitch = pitch;
            this.hasPrevCam = true;
        }

        return new State(this.smoothActivity, this.slash, this.slashDir, swingHand, camShiftX, camShiftY);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private void renderMask(RenderTarget mainTarget, GpuSampler sampler) {
        PostFx.pass("Blade HandsTest mask", PostPipelines.HAND_MASK, this.maskRawTarget, pass -> {
            pass.setUniform("HandMaskUniforms", this.maskUniforms);
            pass.bindTexture("BeforeTexture", this.beforeTarget.getColorTextureView(), sampler);
            pass.bindTexture("AfterTexture", mainTarget.getColorTextureView(), sampler);
            pass.bindTexture("BeforeDepth", this.beforeTarget.getDepthTextureView(), sampler);
            pass.bindTexture("AfterDepth", mainTarget.getDepthTextureView(), sampler);
        });
        PostFx.pass("Blade HandsTest mask smooth", PostPipelines.HAND_MASK_SMOOTH, this.maskTarget, pass -> {
            pass.setUniform("HandMaskUniforms", this.maskUniforms);
            pass.bindTexture("RawMask", this.maskRawTarget.getColorTextureView(), sampler);
        });
    }

    private void renderTrail(GpuSampler sampler) {
        TextureTarget source = this.trailUsesA ? this.trailA : this.trailB;
        TextureTarget destination = this.trailUsesA ? this.trailB : this.trailA;
        PostFx.pass("Blade HandsTest trail", PostPipelines.HANDS_TEST_TRAIL, destination, pass -> {
            pass.setUniform("HandsTestTrailUniforms", this.trailUniforms);
            pass.bindTexture("PrevSampler", source.getColorTextureView(), sampler);
            pass.bindTexture("SceneSampler", this.afterTarget.getColorTextureView(), sampler);
            pass.bindTexture("MaskSampler", this.maskTarget.getColorTextureView(), sampler);
        });
    }

    private void renderFire(RenderTarget mainTarget, GpuTextureView blurredTrail, GpuSampler sampler) {
        PostFx.pass("Blade HandsTest fire", PostPipelines.HANDS_TEST_FIRE, mainTarget, pass -> {
            pass.setUniform("HandsTestFireUniforms", this.fireUniforms);
            pass.bindTexture("SceneSampler", this.afterTarget.getColorTextureView(), sampler);
            pass.bindTexture("SmokeSampler", blurredTrail, sampler);
            pass.bindTexture("MaskSampler", this.maskTarget.getColorTextureView(), sampler);
        });
    }

    private void writeMaskUniforms(int width, int height) {
        PostFx.writeUniforms(this.maskUniforms, MASK_UNIFORM_SIZE, builder -> builder
                .putVec2(1.0F / width, 1.0F / height)
                .putVec2(1.0F, 1.0F));
    }

    private void writeTrailUniforms(HandsTestFeature feature, int width, int height, float time, State state) {
        int color = feature.resolvedColor();
        PostFx.writeUniforms(this.trailUniforms, TRAIL_UNIFORM_SIZE, builder -> builder
                .putVec2((float) width, (float) height)
                .putFloat(time)
                .putFloat(feature.intensity.getValue().floatValue())
                .putFloat(feature.speed.getValue().floatValue())
                .putFloat(feature.length.getValue().floatValue())
                .putFloat(feature.trailSoftness.getValue().floatValue())
                .putFloat(feature.trailBlur.getValue().floatValue())
                .putFloat(feature.smoke.getValue().floatValue())
                .putFloat(state.activity())
                .putFloat(feature.trailFade.getValue().floatValue())
                .putFloat(state.slash())
                .putFloat(state.slashDir())
                .putFloat(state.swingHand())
                .putVec2(state.camShiftX(), state.camShiftY())
                .putVec4(channel(ColorUtil.red(color)), channel(ColorUtil.green(color)), channel(ColorUtil.blue(color)), 1.0F));
    }

    private void writeFireUniforms(HandsTestFeature feature, int width, int height, float time, float activity) {
        int color = feature.resolvedColor();
        PostFx.writeUniforms(this.fireUniforms, FIRE_UNIFORM_SIZE, builder -> builder
                .putVec2((float) width, (float) height)
                .putFloat(time)
                .putFloat(feature.intensity.getValue().floatValue())
                .putFloat(feature.handSoftness.getValue().floatValue())
                .putFloat(feature.handBlur.getValue().floatValue())
                .putFloat(feature.smoke.getValue().floatValue())
                .putFloat(activity)
                .putVec4(channel(ColorUtil.red(color)), channel(ColorUtil.green(color)), channel(ColorUtil.blue(color)), 1.0F));
    }

    private static float channel(int value) {
        return value / 255.0F;
    }

    private void ensureTargets(int width, int height) {
        boolean resetTrail = this.trailA == null || this.trailA.width != width || this.trailA.height != height;
        this.beforeTarget = ensureTarget(this.beforeTarget, "furryhook-handstest-before", width, height, true, PostPipelines.EFFECT_FORMAT);
        this.afterTarget = ensureTarget(this.afterTarget, "furryhook-handstest-after", width, height, false, PostPipelines.EFFECT_FORMAT);
        this.maskRawTarget = ensureTarget(this.maskRawTarget, "furryhook-handstest-mask-raw", width, height, false, PostPipelines.MASK_RAW_FORMAT);
        this.maskTarget = ensureTarget(this.maskTarget, "furryhook-handstest-mask", width, height, false, PostPipelines.EFFECT_FORMAT);
        this.trailA = ensureTarget(this.trailA, "furryhook-handstest-trail-a", width, height, false, PostPipelines.EFFECT_FORMAT);
        this.trailB = ensureTarget(this.trailB, "furryhook-handstest-trail-b", width, height, false, PostPipelines.EFFECT_FORMAT);
        if (resetTrail) {
            clear(this.trailA);
            clear(this.trailB);
        }
    }

    private static TextureTarget ensureTarget(TextureTarget target, String label, int width, int height, boolean useDepth, com.mojang.blaze3d.GpuFormat format) {
        if (target != null && target.width == width && target.height == height) {
            return target;
        }
        if (target != null) {
            target.destroyBuffers();
        }
        return new TextureTarget(label, width, height, useDepth, format);
    }

    private static void clear(TextureTarget target) {
        if (target != null && target.getColorTexture() != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                    target.getColorTexture(), new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
        }
    }

    private boolean resourcesReady() {
        return ready(this.beforeTarget) && ready(this.afterTarget) && ready(this.maskRawTarget)
                && ready(this.maskTarget) && ready(this.trailA) && ready(this.trailB)
                && this.beforeTarget.getDepthTexture() != null && this.beforeTarget.getDepthTextureView() != null;
    }

    private static boolean ready(TextureTarget target) {
        return target != null && target.getColorTexture() != null && target.getColorTextureView() != null;
    }

    private static boolean validMainTarget(RenderTarget target) {
        return target != null && target.width > 0 && target.height > 0
                && target.getColorTexture() != null && target.getColorTextureView() != null
                && target.getDepthTexture() != null && target.getDepthTextureView() != null;
    }

    public void release() {
        this.beforeTarget = destroy(this.beforeTarget);
        this.afterTarget = destroy(this.afterTarget);
        this.maskRawTarget = destroy(this.maskRawTarget);
        this.maskTarget = destroy(this.maskTarget);
        this.trailA = destroy(this.trailA);
        this.trailB = destroy(this.trailB);
        this.blur.release();
        this.maskUniforms.close();
        this.trailUniforms.close();
        this.fireUniforms.close();
    }

    private static TextureTarget destroy(TextureTarget target) {
        if (target != null) {
            target.destroyBuffers();
        }
        return null;
    }
}
