package win.furryhook.utils.render.world;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import win.furryhook.feature.impl.visual.RtxFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.post.PostPipelines;
import win.furryhook.utils.render.post.PostTarget;

import java.nio.ByteBuffer;

/**
 * Volumetric light shafts, done as a post-process rather than as a raymarch:
 * a half-res occlusion buffer keeps whatever the light reaches the camera
 * through and blacks out everything solid, then a radial accumulation smears
 * that toward the light. The gaps between the geometry come out as shafts on
 * their own, which is why this costs one blur instead of a shadow map.
 *
 * <p>The trade is that the light has to be roughly on screen - a shaft needs a
 * screen position to be smeared toward. That is handled honestly by fading the
 * whole effect out as the light leaves the viewport rather than by letting it
 * snap off, and the pass is skipped entirely once the fade reaches zero.
 */
public final class RtxRayRenderer {
    /** How far out the light is placed before projecting - well past any geometry. */
    private static final float LIGHT_DISTANCE = 512.0F;

    /**
     * Below this, dividing the projected position by {@code w} is numerically
     * worthless and the shafts would swing across the screen with a fraction of a
     * degree of camera turn. Same guard the atmosphere pass uses on its bolts.
     */
    private static final float NEAR_PLANE_GUARD = 0.5F;

    /** How far outside the viewport the light may sit before the effect is gone. */
    private static final float OFFSCREEN_REACH = 0.35F;

    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .get();

    private final GpuBuffer uniforms = PostFx.createUniforms("Blade RTX Ray UBO", UNIFORM_SIZE);
    private final PostTarget occlusion = new PostTarget(
            "furryhook-rtx-ray-occlusion",
            PostPipelines.RTX_RAY_FORMAT,
            false
    );
    private final Vector4f lightClip = new Vector4f();

    public void render(RtxFeature feature, CameraRenderState cameraState, SkyRenderState skyState) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (!valid(mainTarget) || cameraState == null || !cameraState.initialized) {
            return;
        }

        Matrix4f levelProjection = Render3DUtil.levelProjectionCopy();
        if (levelProjection == null) {
            return;
        }
        Matrix4f viewProjection = levelProjection.mul(cameraState.viewRotationMatrix);

        // Vanilla drives sun and moon off one angle, and the moon is simply the sun
        // reflected through the origin - so both cases are the same two lines with
        // a sign, and the light never has to be "picked" per frame.
        float sunAngle = skyState != null ? skyState.sunAngle : 0.0F;
        float sunUp = (float) Math.cos(sunAngle);
        float sunEast = -(float) Math.sin(sunAngle);
        boolean daylight = sunUp >= 0.0F;
        if (!daylight && !feature.moonRays.getValue()) {
            return;
        }

        float directionX = daylight ? sunEast : -sunEast;
        float directionY = daylight ? sunUp : -sunUp;
        float elevation = Math.abs(sunUp);

        // Fades across the horizon so the source swapping from sun to moon is
        // invisible, and the moon is dimmer than the sun by roughly the amount the
        // rest of the client already assumes it is.
        float horizonFade = Math.clamp(elevation * 5.0F, 0.0F, 1.0F);
        // Shafts are a low-sun phenomenon: at noon the light comes down the view
        // ray and there is nothing to see edge-on.
        float elevationShape = 0.4F + 0.6F * (1.0F - elevation);
        float sourceStrength = horizonFade * elevationShape * (daylight ? 1.0F : 0.32F);
        if (sourceStrength <= 0.001F) {
            return;
        }

        this.lightClip.set(
                directionX * LIGHT_DISTANCE,
                directionY * LIGHT_DISTANCE,
                0.0F,
                1.0F
        );
        viewProjection.transform(this.lightClip);
        if (this.lightClip.w <= NEAR_PLANE_GUARD) {
            // The light is behind the camera; there is no screen point to march to.
            return;
        }
        float lightU = (this.lightClip.x / this.lightClip.w) * 0.5F + 0.5F;
        float lightV = (this.lightClip.y / this.lightClip.w) * 0.5F + 0.5F;

        float screenFade = offscreenFade(lightU, lightV);
        if (screenFade <= 0.001F) {
            return;
        }

        int halfWidth = Math.max(1, mainTarget.width / 2);
        int halfHeight = Math.max(1, mainTarget.height / 2);
        TextureTarget occlusionTarget = this.occlusion.ensure(halfWidth, halfHeight);
        if (occlusionTarget == null || occlusionTarget.getColorTextureView() == null) {
            return;
        }

        float aspect = mainTarget.height > 0
                ? (float) mainTarget.width / mainTarget.height
                : 1.0F;
        writeUniforms(feature, lightU, lightV, aspect, screenFade * sourceStrength);

        // The occlusion buffer reads the main target while rendering into its own
        // half-res one, so no scene copy is needed here - and the composite is
        // additive, so it does not need one either.
        PostFx.pass(
                "Blade RTX Ray Occlusion",
                PostPipelines.RTX_RAY_OCCLUSION,
                occlusionTarget.getColorTextureView(),
                pass -> {
                    pass.setUniform("RtxRayUniforms", this.uniforms);
                    pass.bindTexture("SceneSampler", mainTarget.getColorTextureView(), PostFx.linearSampler());
                    pass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), PostFx.nearestSampler());
                }
        );

        PostFx.pass("Blade RTX Rays", PostPipelines.RTX_RAYS, mainTarget, pass -> {
            pass.setUniform("RtxRayUniforms", this.uniforms);
            pass.bindTexture("OcclusionSampler", occlusionTarget.getColorTextureView(), PostFx.linearSampler());
        });
    }

    private void writeUniforms(RtxFeature feature,
                               float lightU,
                               float lightV,
                               float aspect,
                               float strengthScale) {
        int tint = feature.rayColor.getValue();
        float samples = feature.raySamples.getValue().floatValue();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                    .putVec4(lightU, lightV, aspect, strengthScale)
                    .putVec4(
                            ColorUtil.red(tint) / 255.0F,
                            ColorUtil.green(tint) / 255.0F,
                            ColorUtil.blue(tint) / 255.0F,
                            feature.rayTint.getValue().floatValue()
                    )
                    .putVec4(
                            samples,
                            feature.rayDensity.getValue().floatValue(),
                            feature.rayDecay.getValue().floatValue(),
                            // Spread is exposed as a radius around the light in
                            // screen heights, which is the number a player can
                            // actually picture. The shader wants the Gaussian
                            // coefficient, which is its inverse square.
                            1.0F / Math.max(
                                    feature.raySpread.getValue().floatValue()
                                            * feature.raySpread.getValue().floatValue(),
                                    0.0025F
                            )
                    )
                    .putVec4(
                            // Only genuinely blown-out geometry seeds a shaft;
                            // ordinary lit stone is nowhere near this.
                            0.72F,
                            feature.rayEmissive.getValue().floatValue(),
                            // The accumulation already divides by the sample count,
                            // so this is the exposure the slider actually sets. The
                            // constant lands the default at "clearly there" rather
                            // than at the blown-out haze the technique defaults to
                            // if you let the raw sum through.
                            feature.rayStrength.getValue().floatValue() * 2.0F,
                            PostFx.shaderTime()
                    )
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.uniforms.slice(), data);
        }
    }

    /**
     * 1 while the light is inside the viewport, ramping to 0 over {@link
     * #OFFSCREEN_REACH} outside it. Without this the shafts would vanish the
     * instant the sun crossed the screen edge, which is exactly when the player is
     * looking at them.
     */
    private static float offscreenFade(float u, float v) {
        float outsideU = Math.max(0.0F, Math.abs(u - 0.5F) - 0.5F);
        float outsideV = Math.max(0.0F, Math.abs(v - 0.5F) - 0.5F);
        float outside = (float) Math.sqrt(outsideU * outsideU + outsideV * outsideV);
        return 1.0F - Math.clamp(outside / OFFSCREEN_REACH, 0.0F, 1.0F);
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
        this.occlusion.release();
        this.uniforms.close();
    }
}
