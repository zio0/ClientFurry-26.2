package win.furryhook.utils.render.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

public final class BladeGuiPipelines {
    public static final RenderPipeline RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/rect"))
            .withVertexShader(Identifier.parse("furryhook:core/rect"))
            .withFragmentShader(Identifier.parse("furryhook:core/rect"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();
    public static final RenderPipeline TEXT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/text"))
            .withVertexShader(Identifier.parse("furryhook:core/text"))
            .withFragmentShader(Identifier.parse("furryhook:core/text"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();
    public static final RenderPipeline TEXTURE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/texture"))
            .withVertexShader(Identifier.parse("furryhook:core/texture"))
            .withFragmentShader(Identifier.parse("furryhook:core/texture"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();

    public static final RenderPipeline GLASS_SHADOW = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/glass_shadow"))
            .withVertexShader(Identifier.parse("furryhook:core/rect"))
            .withFragmentShader(Identifier.parse("furryhook:core/rect"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();
    public static final RenderPipeline BLUR_RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/blur_rect"))
            .withVertexShader(Identifier.parse("furryhook:core/blur_rect"))
            .withFragmentShader(Identifier.parse("furryhook:core/blur_rect"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();
    public static final RenderPipeline COLOR_GRID = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.parse("furryhook:gui/color_grid"))
            .withVertexShader(Identifier.parse("furryhook:core/color_grid"))
            .withFragmentShader(Identifier.parse("furryhook:core/color_grid"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .build();

    private BladeGuiPipelines() {
    }
}
