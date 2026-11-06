package win.furryhook.mixin.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import win.furryhook.utils.render.gui.GuiPipelines;
import win.furryhook.utils.render.jump.JumpCircleRenderer;
import win.furryhook.utils.render.jump.JumpGlowRenderer;
import win.furryhook.utils.render.jump.JumpWaveRenderer;
import win.furryhook.utils.render.particles.WorldParticleRenderer;
import win.furryhook.utils.render.target.AuraMarkerRenderer;
import win.furryhook.utils.render.world.WorldMeshRenderer;
import win.furryhook.utils.render.particles.ProceduralParticleRenderer;
import win.furryhook.utils.render.post.EmissiveBloom;
import win.furryhook.utils.render.post.PostPipelines;
import win.furryhook.utils.render.world.GlowStripRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {
    @Shadow
    private static RenderPipeline register(RenderPipeline renderPipeline) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void registerGuiPipelines(CallbackInfo ci) {
        register(GuiPipelines.RECT);
        register(GuiPipelines.TEXT);
        register(GuiPipelines.TEXTURE);
        register(GuiPipelines.GLASS_SHADOW);
        register(GuiPipelines.BLUR_RECT);
        register(GuiPipelines.COLOR_GRID);
        register(GuiPipelines.GUI_BLUR_DOWN);
        register(GuiPipelines.GUI_BLUR_UP);
        for (RenderPipeline pipeline : GuiPipelines.itemDownscaleVariants()) {
            register(pipeline);
        }
        register(PostPipelines.ESP_KAWASE_DOWN);
        register(PostPipelines.ESP_KAWASE_UP);
        register(PostPipelines.HDR_KAWASE_DOWN);
        register(PostPipelines.HDR_KAWASE_UP);
        for (RenderPipeline pipeline : ProceduralParticleRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : GlowStripRenderer.pipelines()) {
            register(pipeline);
        }
        register(EmissiveBloom.COMPOSITE);
        register(PostPipelines.HAND_PLASMA);
        register(PostPipelines.POINT_LIGHTS);
        register(PostPipelines.WORLD_SKY_CLOUDS_DEEP_SPACE);
        register(PostPipelines.WORLD_SKY_CLOUDS_NEBULA);
        register(PostPipelines.WORLD_SKY_CLOUDS_PLASMA);
        register(PostPipelines.WORLD_SKY_DEEP_SPACE);
        register(PostPipelines.WORLD_SKY_NEBULA);
        register(PostPipelines.WORLD_SKY_PLASMA);
        register(PostPipelines.WORLD_SATURATION);
        register(PostPipelines.CHAMS_SOLID);
        register(PostPipelines.CHAMS_PLASMA);
        register(PostPipelines.CHAMS_NEBULA);
        register(PostPipelines.CHAMS_GLASS);
        register(PostPipelines.CHAMS_OUTLINE);
        register(PostPipelines.CHAMS_INTERNAL);
        register(PostPipelines.CHAMS_GLOW);
        register(PostPipelines.CHAMS_GLOW_ADDITIVE);
        register(PostPipelines.RTX_REFLECTIONS);
        register(PostPipelines.RTX_RAY_OCCLUSION);
        register(PostPipelines.RTX_RAYS);

        // Everything below was built and drawn with, but never handed to Minecraft.
        //
        // Registration is not decoration. RenderPipelines is the list the game walks when the
        // resource manager reloads shaders: registered programs are recompiled and the old ones
        // disposed, and a pipeline the game has never been told about is not on that list. It gets
        // compiled lazily on next use instead, and the program it replaces is left behind in the
        // driver with nothing holding a handle to it.
        //
        // That is why the leak tracked world switches rather than wall clock. Standing still cost
        // ~32 MB/s; a session spent hopping warps and servers, with two full resource reloads in it,
        // cost ~128 MB/s and died in six and a half minutes instead of twenty-seven. Thirty
        // unregistered programs, orphaned again on every reload.
        //
        // These are not optional extras either: ShaderHands alone is seventeen of them, and it,
        // Atmosphere, JumpCircles, Trails and Aura are all things He plays with switched on.
        for (RenderPipeline pipeline : JumpCircleRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : JumpGlowRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : JumpWaveRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : AuraMarkerRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : WorldParticleRenderer.pipelines()) {
            register(pipeline);
        }
        for (RenderPipeline pipeline : WorldMeshRenderer.pipelines()) {
            register(pipeline);
        }

        register(PostPipelines.ATMOSPHERE_FOG);
        register(PostPipelines.SHADER_HANDS);
        register(PostPipelines.HANDS_TEST_FIRE);
        register(PostPipelines.HANDS_TEST_TRAIL);
        register(PostPipelines.HAND_BLUR_DOWN);
        register(PostPipelines.HAND_BLUR_UP);
        register(PostPipelines.HAND_ELECTRIC);
        register(PostPipelines.HAND_EMBERS);
        register(PostPipelines.HAND_FILL);
        register(PostPipelines.HAND_GLASS);
        register(PostPipelines.HAND_HALO);
        register(PostPipelines.HAND_MASK);
        register(PostPipelines.HAND_MASK_SMOOTH);
        register(PostPipelines.HAND_NEBULA);
        register(PostPipelines.HAND_OUTLINE);
        register(PostPipelines.HAND_PRISM);
        register(PostPipelines.HAND_TRAIL);
    }
}
