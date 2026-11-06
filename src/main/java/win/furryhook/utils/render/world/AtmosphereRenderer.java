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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import win.furryhook.feature.impl.visual.AtmosphereFeature;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.post.PostPipelines;
import win.furryhook.utils.render.post.PostTarget;

import java.nio.ByteBuffer;

public final class AtmosphereRenderer {
    // The density setting is a friendly 0.05-2.0 slider, but it's used
    // directly as a per-block extinction coefficient multiplied by ray
    // lengths up to SKY_DISTANCE (600 blocks) in the shader. Unscaled, even
    // the slider's minimum saturated opticalDepth to fully opaque at the
    // horizon (and quickly up close too, which is what made nearby ground
    // visibly "swim" while walking). This maps the whole slider range onto
    // sensible physical density instead.
    private static final float DENSITY_SCALE = 0.005F;

    /**
     * Same idea as {@link #DENSITY_SCALE}, but for the haze deck: at 1.0 the whole
     * horizon would be milk. This puts the default 0.6 at ~0.012 per block, which
     * is a bank you can see forty blocks into and nothing beyond a hundred and
     * fifty - the range ground mist actually has.
     */
    private static final float HAZE_DENSITY_SCALE = 0.02F;

    /**
     * World coordinates are wrapped before reaching the shader: at a few hundred
     * thousand blocks a float loses enough mantissa that the fog noise lattice
     * visibly quantizes. The wrap is far enough out that crossing it is a
     * once-in-a-world event.
     */
    private static final double ORIGIN_WRAP = 65536.0D;

    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .get();

    private final GpuBuffer uniforms = PostFx.createUniforms("Blade Atmosphere UBO", UNIFORM_SIZE);
    private final PostTarget sceneCopy = new PostTarget("furryhook-atmosphere-scene", PostPipelines.EFFECT_FORMAT, false);
    private final Vector4f boltTop = new Vector4f();
    private final Vector4f boltGround = new Vector4f();

    public void render(AtmosphereFeature feature, CameraRenderState cameraState, SkyRenderState skyState) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (!valid(mainTarget) || cameraState == null || !cameraState.initialized) {
            return;
        }

        StormState storm = StormState.INSTANCE;
        storm.update(feature, cameraState.pos);

        // Target ESP's "Молнии" mode keeps this pass armed for as long as Aura's
        // mode is selected, not just while a bolt is actually live - most of that
        // time there is nothing to draw, so the scene copy and shader pass below
        // are skipped instead of paying for a full-screen effect on every idle
        // frame between hits. This also stops the GPU resources further down from
        // being torn down and rebuilt on every strike (see WorldEffects), which
        // was the main cost of this mode.
        boolean ambientActive = feature.isEnabled() && feature.usesAtmosphere();
        if (!ambientActive && !storm.hasVisibleStrike()) {
            return;
        }

        Matrix4f levelProjection = Render3DUtil.levelProjectionCopy();
        if (levelProjection == null) {
            return;
        }
        Matrix4f viewProjection = levelProjection.mul(cameraState.viewRotationMatrix);
        Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();

        boolean depthZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();

        TextureTarget scene = this.sceneCopy.ensure(mainTarget.width, mainTarget.height);
        if (scene == null || scene.getColorTexture() == null) {
            return;
        }

        writeUniforms(
                feature,
                skyState,
                storm,
                cameraState,
                viewProjection,
                inverseViewProjection,
                depthZeroToOne,
                mainTarget.width,
                mainTarget.height
        );

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(),
                scene.getColorTexture(),
                0, 0, 0, 0, 0,
                mainTarget.width,
                mainTarget.height
        );

        PostFx.pass("Blade Atmosphere Fog", PostPipelines.ATMOSPHERE_FOG, mainTarget, pass -> {
            pass.setUniform("AtmosphereUniforms", this.uniforms);
            pass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), PostFx.nearestSampler());
            pass.bindTexture("SceneSampler", scene.getColorTextureView(), PostFx.linearSampler());
        });
    }

    private void writeUniforms(AtmosphereFeature feature,
                               SkyRenderState skyState,
                               StormState storm,
                               CameraRenderState cameraState,
                               Matrix4f viewProjection,
                               Matrix4f inverseViewProjection,
                               boolean depthZeroToOne,
                               int width,
                               int height) {
        float starBrightness = skyState != null ? skyState.starBrightness : 0.0F;

        int fogColor = ColorMode.resolve(feature.fogColorMode, feature.fogColor);
        if (feature.syncWithSky.getValue() && skyState != null) {
            fogColor = ColorUtil.lerp(fogColor, skyState.skyColor, feature.skySync.getValue().floatValue());

            // Vanilla already computes this: alpha is how close we are to
            // sunrise/sunset (0 the rest of the day), rgb is the warm glow
            // color. Blend the fog toward it by that same intensity.
            int sunsetColor = skyState.sunriseAndSunsetColor;
            float sunsetWeight = Math.min(
                    (ColorUtil.alpha(sunsetColor) / 255.0F) * feature.sunsetBoost.getValue().floatValue(),
                    1.0F
            );
            if (sunsetWeight > 0.001F) {
                fogColor = ColorUtil.lerp(fogColor, sunsetColor, sunsetWeight);
            }
        }

        // Target ESP's "Молнии" mode can force this whole pass to run with the
        // Atmosphere module's master toggle off, purely to draw the bolt it just
        // called down - fog/rain/ambient-lightning must stay off in that case, so
        // they are additionally gated on the feature actually being enabled.
        boolean masterOn = feature.isEnabled();

        boolean usesFog = masterOn && feature.usesFog();
        float density = usesFog
                ? feature.fogDensity.getValue().floatValue() * DENSITY_SCALE
                : 0.0F;
        float ambientStrength = usesFog
                ? feature.ambientStrength.getValue().floatValue() * (1.0F - 0.55F * starBrightness)
                : 0.0F;
        if (usesFog && feature.stormFog.getValue()) {
            // A storm thickens the air and kills the ambient at the same time -
            // driving both from one number is what stops the fog and the
            // lightning from looking like two unrelated effects.
            float intensity = storm.intensity();
            density *= 1.0F + intensity * 1.35F;
            ambientStrength *= 1.0F - 0.4F * intensity;
        }
        float inverseHeight = 1.0F / Math.max(feature.fogHeight.getValue().floatValue(), 1.0F);
        float aspect = height > 0 ? (float) width / height : 1.0F;

        // Sun above the horizon, moon below it. Both contributions hit zero as the
        // light source crosses the horizon, so the direction flipping over there
        // is invisible instead of popping the whole fog volume.
        float sunAngle = skyState != null ? skyState.sunAngle : 0.0F;
        float sunUp = (float) Math.cos(sunAngle);
        float sunEast = -(float) Math.sin(sunAngle);
        boolean daylight = sunUp >= 0.0F;
        float lightAmount = usesFog
                ? (Math.clamp(sunUp * 6.0F, 0.0F, 1.0F) + Math.clamp(-sunUp * 6.0F, 0.0F, 1.0F) * 0.3F)
                * (1.0F - 0.7F * storm.intensity())
                : 0.0F;

        // Not gated on usesLightning()/masterOn: a strike only ever ends up in
        // StormState's list when the ambient driver put it there (already gated on
        // masterOn internally) or Target ESP's "Молнии" mode called one down, so the
        // presence of a strike is itself the gate.
        float flash = storm.flashStrength() * feature.flashStrength.getValue().floatValue();

        int boltColor = feature.boltColor.getValue();
        float boltRed = ColorUtil.red(boltColor) / 255.0F;
        float boltGreen = ColorUtil.green(boltColor) / 255.0F;
        float boltBlue = ColorUtil.blue(boltColor) / 255.0F;

        float brightness = 0.0F;
        float seed = 0.0F;
        float strikeDistance = 0.0F;
        StormState.Strike strike = storm.activeStrike();
        if (strike != null
                && project(viewProjection, cameraState.pos, strike.topX, strike.topY, strike.topZ, this.boltTop)
                && project(viewProjection, cameraState.pos, strike.bottomX, strike.bottomY, strike.bottomZ, this.boltGround)) {
            brightness = StormState.boltEnvelope(strike);
            seed = strike.seed;
            // Recomputed from the real camera every frame rather than trusting the
            // distance baked in at spawn time: ambient strikes are far enough out
            // that the camera barely moves relative to them, but Target ESP's
            // "Молнии" mode calls one down right on top of a melee hit, where the
            // gap between the player's own position (used to size the strike) and
            // third-person's camera pulled back several blocks behind it is large
            // enough to throw the shader's occlusion test off and hide the bolt.
            double dx = strike.bottomX - cameraState.pos.x();
            double dy = strike.bottomY - cameraState.pos.y();
            double dz = strike.bottomZ - cameraState.pos.z();
            strikeDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        // Core radius in pixels, converted to the shader's aspect-corrected uv
        // space where one unit is the screen height.
        float radius = Math.max(feature.boltWidth.getValue().floatValue(), 0.1F) / Math.max(height, 1);
        float glow = radius * 6.0F + 0.005F;

        boolean snow = feature.usesSnow();
        int rainColor = feature.rainColor.getValue();
        // Capped well under 1: the cell hash is compared against this, so letting
        // it reach 1 would put a drop in every single cell and read as a wall.
        float rainAmount = masterOn && feature.usesRain()
                ? Math.min(
                        storm.intensity()
                                * feature.rainDensity.getValue().floatValue()
                                * storm.skyVisibility()
                                * 0.7F,
                        0.72F)
                : 0.0F;
        // Ramps in with light rain and then holds - how many rings there are is
        // the density's job, not the brightness's.
        float rippleStrength = feature.ripples.getValue() && !snow && rainAmount > 0.0F
                ? feature.rippleStrength.getValue().floatValue() * Math.min(1.0F, rainAmount * 2.5F)
                : 0.0F;

        // Ground mist forms after sunset, so the layer is driven by how far the sun
        // is below the horizon rather than by a clock. The ramp starts a little
        // before the sun is fully down, which is when it actually starts to gather.
        float nightFactor = Math.clamp((-sunUp + 0.12F) * 3.0F, 0.0F, 1.0F);
        boolean usesHaze = masterOn && feature.usesNightHaze();
        float hazeNight = feature.hazeNightOnly.getValue() ? nightFactor : 1.0F;
        float hazeDensity = usesHaze
                ? feature.hazeAmount.getValue().floatValue() * HAZE_DENSITY_SCALE * hazeNight
                : 0.0F;
        float hazeGlow = feature.hazeGlow.getValue().floatValue();
        int hazeColor = ColorMode.resolve(feature.hazeColorMode, feature.hazeColor);
        float hazeDeck = feature.hazeFollowGround.getValue()
                ? storm.groundHeight() + feature.hazeOffset.getValue().floatValue()
                : feature.hazeLevel.getValue().floatValue();
        // Passed as the camera's height ABOVE the deck, not as an absolute Y: the
        // shader integrates from the camera outward, and handing it the difference
        // keeps both sides in the small numbers a float is accurate in.
        float hazeCameraOffset = (float) (cameraState.pos.y() - hazeDeck);
        float hazeInverseThickness = 1.0F / Math.max(feature.hazeThickness.getValue().floatValue(), 0.5F);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                    .putMat4f(inverseViewProjection)
                    .putVec4(
                            ColorUtil.red(fogColor) / 255.0F,
                            ColorUtil.green(fogColor) / 255.0F,
                            ColorUtil.blue(fogColor) / 255.0F,
                            density
                    )
                    .putVec4(
                            inverseHeight,
                            ambientStrength,
                            depthZeroToOne ? 1.0F : 0.0F,
                            aspect
                    )
                    .putVec4(
                            usesFog ? feature.turbulence.getValue().floatValue() : 0.0F,
                            feature.sunScatter.getValue().floatValue(),
                            storm.fogDrift(),
                            storm.intensity()
                    )
                    .putVec4(
                            daylight ? sunEast : -sunEast,
                            daylight ? sunUp : -sunUp,
                            0.0F,
                            lightAmount
                    )
                    .putVec4(
                            (float) wrap(cameraState.pos.x()),
                            (float) cameraState.pos.y(),
                            (float) wrap(cameraState.pos.z()),
                            strikeDistance
                    )
                    .putVec4(storm.windX(), storm.windZ(), storm.windStrength(), 0.0F)
                    .putVec4(
                            // The flash is the bolt's light spilling everywhere, so it
                            // keeps the bolt's hue but washes toward white.
                            boltRed + (1.0F - boltRed) * 0.3F,
                            boltGreen + (1.0F - boltGreen) * 0.3F,
                            boltBlue + (1.0F - boltBlue) * 0.3F,
                            flash
                    )
                    .putVec4(this.boltTop.x, this.boltTop.y, this.boltGround.x, this.boltGround.y)
                    .putVec4(boltRed, boltGreen, boltBlue, glow)
                    .putVec4(
                            brightness,
                            radius,
                            seed,
                            feature.boltBranches.getValue().floatValue()
                    )
                    .putVec4(
                            ColorUtil.red(rainColor) / 255.0F,
                            ColorUtil.green(rainColor) / 255.0F,
                            ColorUtil.blue(rainColor) / 255.0F,
                            feature.rainOpacity.getValue().floatValue()
                    )
                    .putVec4(
                            rainAmount,
                            storm.rainPhase(),
                            snow ? 1.0F : 0.0F,
                            // Lean is sideways drift over fall speed. A raindrop
                            // falls fast and barely tips; a flake falls slowly
                            // enough for the same wind to throw it sideways.
                            snow ? 1.5F : 0.25F
                    )
                    .putVec4(
                            rippleStrength,
                            storm.ripplePhase(),
                            Math.min(rainAmount * 1.4F, 0.85F),
                            0.0F
                    )
                    .putVec4(
                            // Glow is folded into the tint: the shader only ever uses
                            // this color as the scatter term, so a separate brightness
                            // uniform would be one multiply it does per pixel for
                            // something that never changes across the frame.
                            ColorUtil.red(hazeColor) / 255.0F * hazeGlow,
                            ColorUtil.green(hazeColor) / 255.0F * hazeGlow,
                            ColorUtil.blue(hazeColor) / 255.0F * hazeGlow,
                            hazeDensity
                    )
                    .putVec4(
                            hazeInverseThickness,
                            hazeCameraOffset,
                            feature.hazeBanks.getValue().floatValue(),
                            feature.hazeMoonScatter.getValue().floatValue()
                    )
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.uniforms.slice(), data);
        }
    }

    private static double wrap(double value) {
        return value - Math.floor(value / ORIGIN_WRAP) * ORIGIN_WRAP;
    }

    /**
     * Both endpoints must clear this to be trusted - not "on screen", just far
     * enough from the near plane that dividing by {@code w} is stable. Below it,
     * a fraction of a degree of camera turn swings the divided uv wildly, which
     * is what read as shapes drifting across the sky. It is not a viewport
     * check: a point can (and for Target ESP's "Молнии" mode routinely does,
     * since its top sits well above a target only a few blocks away) land far
     * outside [0,1] with a perfectly stable {@code w} - that is just off-screen,
     * and rejecting it hides the whole bolt instead of only its off-screen part,
     * which is why the channel used to disappear unless the top happened to be
     * in view too.
     */
    private static final float NEAR_PLANE_GUARD = 0.5F;

    /**
     * Projects a world point to the same uv space the fragment shader reads,
     * which is the exact inverse of {@code atmosphereReconstruct} - so whatever
     * clip-space convention the backend uses, both sides agree. Returns false
     * only when the point is behind, on, or grazing the near plane (see
     * {@link #NEAR_PLANE_GUARD}); a stable projection far outside the visible
     * uv range is left for the shader's own capsule/reach test to discard.
     */
    private static boolean project(Matrix4f viewProjection,
                                   Vec3 cameraPos,
                                   double x,
                                   double y,
                                   double z,
                                   Vector4f out) {
        out.set(
                (float) (x - cameraPos.x()),
                (float) (y - cameraPos.y()),
                (float) (z - cameraPos.z()),
                1.0F
        );
        viewProjection.transform(out);
        if (out.w <= NEAR_PLANE_GUARD) {
            return false;
        }
        out.x = (out.x / out.w) * 0.5F + 0.5F;
        out.y = (out.y / out.w) * 0.5F + 0.5F;
        return true;
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
        this.uniforms.close();
        StormState.INSTANCE.reset();
    }
}
