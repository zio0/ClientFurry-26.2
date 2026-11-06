package win.furryhook.mixin.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import win.furryhook.utils.render.gui.GuiPipelines;
import win.furryhook.utils.render.gui.GuiBackdrop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow
    @Final
    private List<?> draws;

    @Unique
    private final IntList furryhook$glassSplits = new IntArrayList();
    @Unique
    private boolean furryhook$previousElementWasGlass;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void furryhook$resetGlassSplits(CallbackInfo ci) {
        this.furryhook$glassSplits.clear();
        this.furryhook$previousElementWasGlass = false;
    }

    @Inject(method = "addElementToMesh", at = @At("HEAD"))
    private void furryhook$markGlassRuns(GuiElementRenderState elementState, CallbackInfo ci) {
        boolean glass = furryhook$isGlassPipeline(elementState.pipeline());
        if (glass && !this.furryhook$previousElementWasGlass) {

            this.furryhook$glassSplits.add(this.draws.size());
        }
        this.furryhook$previousElementWasGlass = glass;
    }

    @WrapOperation(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V"
            )
    )
    private void furryhook$captureBackdropBeforeGlassRuns(
            GuiRenderer instance,
            Supplier<String> label,
            RenderTarget mainRenderTarget,
            GpuBufferSlice dynamicTransforms,
            int startIndex,
            int endIndex,
            Operation<Void> original
    ) {
        int cursor = startIndex;
        for (int i = 0; i < this.furryhook$glassSplits.size(); i++) {
            int split = this.furryhook$glassSplits.getInt(i);
            if (split < cursor || split >= endIndex) {
                continue;
            }
            if (split > cursor) {
                original.call(instance, label, mainRenderTarget, dynamicTransforms, cursor, split);
            }
            GuiBackdrop.captureNow();
            cursor = split;
        }
        if (cursor < endIndex) {
            original.call(instance, label, mainRenderTarget, dynamicTransforms, cursor, endIndex);
        }
    }

    @Unique
    private static boolean furryhook$isGlassPipeline(RenderPipeline pipeline) {
        return pipeline == GuiPipelines.BLUR_RECT || pipeline == GuiPipelines.GLASS_SHADOW;
    }

    @WrapOperation(
            method = "submitBlitFromItemAtlas",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/SamplerCache;getRepeat(Lcom/mojang/blaze3d/textures/FilterMode;)Lcom/mojang/blaze3d/textures/GpuSampler;"
            )
    )
    private GpuSampler furryhook$smoothDownscaledItems(
            SamplerCache cache,
            FilterMode filterMode,
            Operation<GpuSampler> original,
            @Local(argsOnly = true) GuiItemRenderState itemState
    ) {
        if (furryhook$isDownscaled(itemState)) {
            return original.call(cache, FilterMode.LINEAR);
        }
        return original.call(cache, filterMode);
    }

    @WrapOperation(
            method = "submitBlitFromItemAtlas",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderPipelines;GUI_TEXTURED_PREMULTIPLIED_ALPHA:Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            )
    )
    private RenderPipeline furryhook$bicubicDownscalePipeline(
            Operation<RenderPipeline> original,
            @Local(argsOnly = true) GuiItemRenderState itemState
    ) {
        if (furryhook$isDownscaled(itemState)) {
            return GuiPipelines.itemDownscale(Minecraft.getInstance().getWindow().getGuiScale());
        }
        return original.call();
    }

    @Unique
    private static boolean furryhook$isDownscaled(GuiItemRenderState itemState) {
        return itemState.pose().m00 < 0.999F;
    }
}
