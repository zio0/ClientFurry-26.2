package win.furryhook.utils.render.world;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import win.furryhook.feature.impl.visual.RtxFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.post.KawaseBlur;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.post.PostPipelines;
import win.furryhook.utils.render.post.PostTarget;

import java.nio.ByteBuffer;

/**
 * Wet ground: screen-space reflections restricted to up-facing surfaces, with a
 * world-space puddle mask and impact ripples so the water pools and wobbles
 * instead of turning every floor into a sheet of chrome.
 *
 * <p>Runs before the Atmosphere pass on purpose - a reflection is part of the
 * scene, so the fog has to be allowed to cover it. Running after would leave
 * puddles cutting bright holes through a hundred blocks of mist.
 */
public final class RtxReflectionRenderer {
    /**
     * Same reason the atmosphere pass wraps: several hundred thousand blocks out, a
     * float has lost enough mantissa that the world-space puddle lattice visibly
     * quantizes.
     */
    private static final double ORIGIN_WRAP = 65536.0D;

    /** Above this the roughness blur is a no-op and not worth four passes. */
    private static final float SHARP_ENOUGH = 0.995F;

    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
            .putMat4f()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .get();

    private final GpuBuffer uniforms = PostFx.createUniforms("Blade RTX Reflection UBO", UNIFORM_SIZE);
    private final PostTarget sceneCopy = new PostTarget(
            "furryhook-rtx-reflection-scene",
            PostPipelines.EFFECT_FORMAT,
            false
    );
    private final KawaseBlur roughBlur = new KawaseBlur("furryhook-rtx-rough");
    private boolean blurActive;

    public void render(RtxFeature feature, CameraRenderState cameraState, SkyRenderState skyState) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (!valid(mainTarget) || cameraState == null || !cameraState.initialized) {
            return;
        }

        WetnessState wet = WetnessState.INSTANCE;
        wet.update(feature);
        float wetness = wet.wetness();
        if (wetness <= 0.004F) {
            // Bone dry - nothing to reflect off, and skipping here is what keeps the
            // module free on a clear day instead of paying for a scene copy to
            // discover there is no work.
            releaseBlur();
            return;
        }

        Matrix4f levelProjection = Render3DUtil.levelProjectionCopy();
        if (levelProjection == null) {
            return;
        }
        Matrix4f viewProjection = levelProjection.mul(cameraState.viewRotationMatrix);
        Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();

        TextureTarget scene = this.sceneCopy.ensure(mainTarget.width, mainTarget.height);
        if (scene == null || scene.getColorTexture() == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(),
                scene.getColorTexture(),
                0, 0, 0, 0, 0,
                mainTarget.width,
                mainTarget.height
        );

        float sharpness = feature.sharpness.getValue().floatValue();
        GpuTextureView blurred;
        if (sharpness >= SHARP_ENOUGH) {
            // The shader still has to bind something to the slot, and the sharp copy
            // is the correct thing to bind: at this sharpness it is never mixed in.
            blurred = scene.getColorTextureView();
            releaseBlur();
        } else {
            blurred = this.roughBlur.run(
                    scene.getColorTextureView(),
                    mainTarget.width,
                    mainTarget.height,
                    2,
                    2.0F,
                    PostFx.linearSampler()
            );
            this.blurActive = true;
        }
        GpuTextureView blurredView = blurred;

        writeUniforms(feature, cameraState, skyState, wet, viewProjection, inverseViewProjection, wetness);

        PostFx.pass("Blade RTX Reflections", PostPipelines.RTX_REFLECTIONS, mainTarget, pass -> {
            pass.setUniform("RtxReflectionUniforms", this.uniforms);
            pass.bindTexture("SceneSampler", scene.getColorTextureView(), PostFx.linearSampler());
            pass.bindTexture("BlurredSampler", blurredView, PostFx.linearSampler());
            pass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), PostFx.nearestSampler());
        });
    }

    private void writeUniforms(RtxFeature feature,
                               CameraRenderState cameraState,
                               SkyRenderState skyState,
                               WetnessState wet,
                               Matrix4f viewProjection,
                               Matrix4f inverseViewProjection,
                               float wetness) {
        boolean depthZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();

        // What a puddle shows when the reflected ray walks off the screen. The sky is
        // the honest answer for an up-facing surface, and vanilla has already worked
        // out what colour the sky is right now.
        int skyColor = skyState != null ? skyState.skyColor : 0xFF6A8CC8;

        float distance = feature.reflectDistance.getValue().floatValue();
        float steps = feature.reflectSteps.getValue().floatValue();

        float rippleAmount = feature.reflectRipples.getValue()
                ? feature.rippleAmount.getValue().floatValue()
                : 0.0F;
        // Rings only exist while drops are actually landing, so they fade with the
        // rain and not with the wetness that outlives it by half a minute.
        float rippleDensity = Math.min(wet.rainAmount() * 1.4F, 0.85F);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                    .putMat4f(inverseViewProjection)
                    .putMat4f(viewProjection)
                    .putVec4(
                            (float) wrap(cameraState.pos.x()),
                            (float) cameraState.pos.y(),
                            (float) wrap(cameraState.pos.z()),
                            depthZeroToOne ? 1.0F : 0.0F
                    )
                    .putVec4(
                            wetness,
                            feature.puddles.getValue().floatValue(),
                            feature.darkening.getValue().floatValue(),
                            feature.surfaceAngle.getValue().floatValue()
                    )
                    .putVec4(
                            steps,
                            distance,
                            feature.reflectThickness.getValue().floatValue(),
                            // Growth per step, solved so the chain always spans the
                            // configured distance no matter how many steps there are:
                            // more steps buy accuracy, not reach.
                            stepGrowth(steps)
                    )
                    .putVec4(
                            feature.fresnel.getValue().floatValue(),
                            feature.sharpness.getValue().floatValue(),
                            feature.reflectStrength.getValue().floatValue(),
                            1.0F / Math.max(distance, 1.0F)
                    )
                    .putVec4(
                            rippleAmount * (rippleDensity > 0.0F ? 1.0F : 0.0F),
                            wet.ripplePhase(),
                            rippleDensity,
                            // The wave field's gradient is already roughly unit
                            // scale, so this is the whole amplitude: a tenth of a
                            // slope is a few degrees of tilt, which is all still
                            // water ever does.
                            feature.waveAmount.getValue().floatValue() * 0.4F
                    )
                    .putVec4(
                            ColorUtil.red(skyColor) / 255.0F,
                            ColorUtil.green(skyColor) / 255.0F,
                            ColorUtil.blue(skyColor) / 255.0F,
                            // A guess, and weighted like one: a fallback that claimed
                            // full confidence would put a hard sky-coloured edge on
                            // every puddle the march ran off the side of.
                            0.55F
                    )
                    .putVec4(
                            1.0F / Math.max(feature.puddleScale.getValue().floatValue(), 0.5F),
                            wet.drift(),
                            0.0F,
                            0.0F
                    )
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.uniforms.slice(), data);
        }
    }

    /**
     * A geometric march spends its resolution where it matters - the first block of
     * the reflected ray - and coasts through the rest. Tuned so a short chain grows
     * fast and a long one barely grows at all, since 64 uniform steps over 28 blocks
     * are already fine enough.
     */
    private static float stepGrowth(float steps) {
        return 1.0F + 2.2F / Math.max(steps, 4.0F);
    }

    private static double wrap(double value) {
        return value - Math.floor(value / ORIGIN_WRAP) * ORIGIN_WRAP;
    }

    private void releaseBlur() {
        if (this.blurActive) {
            this.roughBlur.release();
            this.blurActive = false;
        }
    }

    private static boolean valid(RenderTarget target) {
        return target != null
                && target.width > 0
                && target.height > 0
                && target.getColorTexture() != null
                && target.getColorTextureView() != null
                && target.getDepthTexture() != null
                && target.getDepthTextureView() != null;
    }

    public void release() {
        this.sceneCopy.release();
        releaseBlur();
        this.uniforms.close();
        WetnessState.INSTANCE.reset();
    }
}
