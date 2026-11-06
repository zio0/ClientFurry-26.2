package win.furryhook.utils.render.world;

import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.visual.AtmosphereFeature;
import win.furryhook.feature.impl.player.FullBrightFeature;
import win.furryhook.feature.impl.visual.ChamsFeature;
import win.furryhook.feature.impl.visual.JumpCirclesFeature;
import win.furryhook.feature.impl.visual.LineGlyphesFeature;
import win.furryhook.feature.impl.visual.ParticlesFeature;
import win.furryhook.feature.impl.visual.TrailsFeature;
import win.furryhook.feature.impl.visual.RtxFeature;
import win.furryhook.feature.impl.visual.TrajectoriesFeature;
import win.furryhook.feature.impl.visual.WorldTweaksFeature;
import win.furryhook.utils.render.chams.ChamsPipeline;
import win.furryhook.utils.render.target.TargetMarkers;

import java.util.ArrayList;
import java.util.List;

public final class WorldEffects {
    private static final List<WorldEffect> EFFECTS = new ArrayList<>();

    private WorldEffects() {
    }

    public static void bootstrap() {
        if (!EFFECTS.isEmpty()) {
            return;
        }

        register(WorldEffect.lazy(
                () -> gate(WorldTweaksFeature.getEnabled(), WorldTweaksFeature::usesSky),
                WorldTweaksRenderer::new,
                (feature, renderer, context) -> renderer.renderSky(feature, context.cameraRenderState()),
                WorldTweaksRenderer::release
        ));

        // Ahead of the atmosphere on purpose: a reflection is part of the scene, so
        // the fog has to be allowed to cover it. Behind it, every puddle would cut a
        // bright hole through a hundred blocks of mist.
        register(WorldEffect.lazy(
                () -> gate(RtxFeature.getEnabled(), RtxFeature::usesReflections),
                RtxReflectionRenderer::new,
                (feature, renderer, context) -> renderer.render(
                        feature,
                        context.cameraRenderState(),
                        context.levelRenderState().skyRenderState
                ),
                RtxReflectionRenderer::release
        ));

        register(WorldEffect.lazy(
                WorldEffects::atmosphereRenderTarget,
                AtmosphereRenderer::new,
                (feature, renderer, context) -> renderer.render(
                        feature,
                        context.cameraRenderState(),
                        context.levelRenderState().skyRenderState
                ),
                AtmosphereRenderer::release
        ));

        // Behind the atmosphere for the mirrored reason: shafts ARE the lit volume,
        // so they belong on top of the fog rather than underneath it.
        register(WorldEffect.lazy(
                () -> gate(RtxFeature.getEnabled(), RtxFeature::usesRays),
                RtxRayRenderer::new,
                (feature, renderer, context) -> renderer.render(
                        feature,
                        context.cameraRenderState(),
                        context.levelRenderState().skyRenderState
                ),
                RtxRayRenderer::release
        ));

        register(WorldEffect.lazy(
                () -> gate(FullBrightFeature.getEnabled(), FullBrightFeature::usesShaderLights),
                DynamicLightRenderer::new,
                (feature, renderer, context) -> renderer.render(feature, context.cameraRenderState(), context.tickDelta()),
                DynamicLightRenderer::release
        ));

        register(WorldEffect.lazy(
                ChamsFeature::getEnabled,
                ChamsPipeline::new,
                (feature, pipeline, context) -> pipeline.render(context.levelRenderState(), feature),
                ChamsPipeline::release
        ));

        register(WorldEffect.direct(
                JumpCirclesFeature::getEnabled,
                (feature, context) -> feature.renderWorld()
        ));

        register(WorldEffect.direct(
                TrajectoriesFeature::getEnabled,
                (feature, context) -> feature.renderWorld()
        ));

        register(WorldEffect.direct(
                ParticlesFeature::getEnabled,
                (feature, context) -> feature.renderWorld(context.tickDelta())
        ));

        register(WorldEffect.direct(
                TrailsFeature::getEnabled,
                (feature, context) -> feature.renderWorld(context.tickDelta())
        ));

        register(WorldEffect.direct(
                LineGlyphesFeature::getEnabled,
                (feature, context) -> feature.renderWorld()
        ));

        register(WorldEffect.lazy(
                AuraFeature::getMarkerFeature,
                TargetMarkers::new,
                (feature, markers, context) -> markers.render(feature, context.tickDelta()),
                TargetMarkers::release
        ));

        register(WorldEffect.lazy(
                () -> gate(WorldTweaksFeature.getEnabled(), WorldTweaksFeature::usesSaturation),
                WorldTweaksRenderer::new,
                (feature, renderer, context) -> renderer.renderSaturation(feature),
                WorldTweaksRenderer::release
        ));
    }

    public static void register(WorldEffect effect) {
        EFFECTS.add(effect);
    }

    public static void render(WorldEffectContext context) {
        for (WorldEffect effect : EFFECTS) {
            if (effect.active()) {
                effect.render(context);
            } else {
                effect.release();
            }
        }
    }

    private static <F> F gate(F feature, java.util.function.Predicate<F> enabled) {
        return feature != null && enabled.test(feature) ? feature : null;
    }

    /**
     * Runs the Atmosphere pass either for the module's own weather (fog/rain/lightning,
     * gated on the module actually being enabled) or, with the module off, for as long as
     * Aura's Target ESP is set to "Молнии" - the settings still come from this feature
     * instance since that mode reuses the Atmosphere module's bolt color/width/branches/
     * flash knobs. Gated on the mode being selected rather than on a strike actually being
     * visible right now: the renderer owns real GPU resources (a scene-copy texture and a
     * uniform buffer) that {@link AtmosphereRenderer#release()} tears down whenever this
     * effect goes inactive, and Target ESP's "Молнии" mode fires briefly on every hit - a
     * gate that flapped with each strike's ~1s lifetime would rebuild those resources on
     * every swing. AtmosphereRenderer itself skips the actual work on idle frames instead.
     */
    private static AtmosphereFeature atmosphereRenderTarget() {
        AtmosphereFeature feature = FeatureManager.INSTANCE.getFeature(AtmosphereFeature.class);
        if (feature == null) {
            return null;
        }
        boolean ambient = feature.isEnabled() && feature.usesAtmosphere();
        AuraFeature aura = FeatureManager.INSTANCE.getFeature(AuraFeature.class);
        boolean lightningTargetEsp = aura != null && aura.isEnabled() && aura.usesLightningTargetEsp();
        return ambient || lightningTargetEsp ? feature : null;
    }
}
