package win.furryhook.utils.render.chams;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import win.furryhook.feature.impl.visual.ChamsFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.HurtUtil;
import win.furryhook.utils.render.post.PostPipelines;
import win.furryhook.utils.render.post.KawaseBlur;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.post.PostTarget;

import com.mojang.blaze3d.buffers.Std140SizeCalculator;

public final class ChamsCompositeEffect {
    private static final int MAX_MIPS = 3;
    private static final int STYLE_SIZE = new Std140SizeCalculator().putVec4().putVec4().get();

    private final GpuBuffer styleUniforms = PostFx.createUniforms("Blade Chams Style UBO", STYLE_SIZE);
    private final KawaseBlur maskBlur = new KawaseBlur("furryhook-chams-mask");
    private final KawaseBlur sceneBlur = new KawaseBlur("furryhook-chams-scene");
    private final PostTarget sceneCopy = new PostTarget("furryhook-chams-scene-copy", PostPipelines.EFFECT_FORMAT, false);

    public void render(ChamsMaskRenderer.MaskFrame frame, ChamsFeature feature) {
        RenderTarget mask = frame.mask();
        RenderTarget output = frame.output();
        if (mask == null
                || output == null
                || mask.getColorTextureView() == null
                || output.getColorTextureView() == null) {
            return;
        }
        int width = output.width;
        int height = output.height;
        writeStyle(feature, frame.hurtFactor());
        GpuSampler sampler = PostFx.linearSampler();

        GpuTextureView blurredMask = null;
        if (feature.usesInternal() || feature.hasGlow()) {
            float radius = Math.max(1.0F, feature.glowRadius.getValue().floatValue() * 2.0F);
            blurredMask = this.maskBlur.run(
                    mask.getColorTextureView(),
                    width,
                    height,
                    Math.clamp((int) Math.ceil(radius / 2.0F), 1, MAX_MIPS),
                    Math.clamp(radius, 0.5F, 4.0F),
                    sampler
            );
        }

        if (feature.hasGlass()) {

            TextureTarget scene = this.sceneCopy.ensure(width, height);
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    output.getColorTexture(),
                    scene.getColorTexture(),
                    0, 0, 0, 0, 0,
                    width,
                    height
            );
            GpuTextureView sceneView = scene.getColorTextureView();
            if (feature.glassBlur.getValue() > 0.001D) {
                float radius = feature.glassBlur.getValue().floatValue() / 8.0F;
                sceneView = this.sceneBlur.run(
                        sceneView,
                        width,
                        height,
                        Math.clamp((int) Math.ceil(radius / 2.0F), 1, MAX_MIPS),
                        Math.clamp(radius, 0.5F, 4.0F),
                        sampler
                );
            }
            GpuTextureView finalScene = sceneView;
            stylePass("Blade Chams Glass", PostPipelines.CHAMS_GLASS, output, pass -> {
                pass.bindTexture("SceneSampler", finalScene, sampler);
                pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler);
            });
        }
        if (feature.hasSolid()) {
            stylePass("Blade Chams Solid", PostPipelines.CHAMS_SOLID, output,
                    pass -> pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler));
        }
        if (feature.hasShaderFill()) {
            stylePass(
                    "Blade Chams Shader Fill",
                    feature.shader.is(ChamsFeature.SHADER_NEBULA)
                            ? PostPipelines.CHAMS_NEBULA
                            : PostPipelines.CHAMS_PLASMA,
                    output,
                    pass -> pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler)
            );
        }
        if (feature.usesInternal() && blurredMask != null) {
            GpuTextureView finalBlur = blurredMask;
            stylePass("Blade Chams Internal", PostPipelines.CHAMS_INTERNAL, output, pass -> {
                pass.bindTexture("BlurredSampler", finalBlur, sampler);
                pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler);
            });
        }
        if (feature.hasOutline()) {
            stylePass("Blade Chams Outline", PostPipelines.CHAMS_OUTLINE, output,
                    pass -> pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler));
        }
        if (feature.hasGlow() && blurredMask != null) {
            GpuTextureView finalBlur = blurredMask;
            stylePass(
                    "Blade Chams Glow",
                    feature.additiveBlending.getValue()
                            ? PostPipelines.CHAMS_GLOW_ADDITIVE
                            : PostPipelines.CHAMS_GLOW,
                    output,
                    pass -> {
                        pass.bindTexture("BlurredSampler", finalBlur, sampler);
                        pass.bindTexture("MaskSampler", mask.getColorTextureView(), sampler);
                    }
            );
        }
    }

    private void stylePass(String label,
                           RenderPipeline pipeline,
                           RenderTarget output,
                           java.util.function.Consumer<com.mojang.blaze3d.systems.RenderPass> setup) {
        PostFx.pass(label, pipeline, output, pass -> {
            pass.setUniform("ChamsStyle", this.styleUniforms);
            setup.accept(pass);
        });
    }

    private void writeStyle(ChamsFeature feature, float hurtFactor) {

        int color = HurtUtil.blend(feature.resolvedColor(), hurtFactor, 1.0F);
        PostFx.writeUniforms(this.styleUniforms, STYLE_SIZE, builder -> builder
                .putVec4(
                        ColorUtil.red(color) / 255.0F,
                        ColorUtil.green(color) / 255.0F,
                        ColorUtil.blue(color) / 255.0F,
                        feature.opacity.getValue().floatValue()
                )
                .putVec4(
                        PostFx.shaderTime() * feature.shaderSpeed.getValue().floatValue(),
                        feature.mirror.getValue() ? 1.0F : 0.0F,
                        feature.outlineThickness.getValue().floatValue(),
                        feature.glowStrength.getValue().floatValue()
                ));
    }

    public void release() {
        this.maskBlur.release();
        this.sceneBlur.release();
        this.sceneCopy.release();
        this.styleUniforms.close();
    }
}
