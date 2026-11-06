package win.furryhook.utils.render.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import win.furryhook.utils.math.MathUtil;
import org.joml.Matrix3x2fc;

public final class BlurRectRenderState extends UiElementRenderState {
    private static final float SPECULAR_SCALE = 1000.0F;

    private final float x0;
    private final float y0;
    private final float x1;
    private final float y1;
    private final int tintColor;
    private final float radius;
    private final float opacity;
    private final float bevel;
    private final float specular;
    private final float dispersion;
    private final float frost;
    private final float lightX;
    private final float lightY;
    private final TextureSetup textureSetup;

    BlurRectRenderState(
            Matrix3x2fc pose,
            float x0,
            float y0,
            float x1,
            float y1,
            int tintColor,
            float radius,
            float blurRadiusPx,
            float opacity,
            float bevel,
            float specular,
            float dispersion,
            float frost,
            float lightX,
            float lightY,
            GpuTextureView backdropView,
            ScreenRectangle scissor
    ) {
        super(pose, scissor, x0, y0, x1 - x0, y1 - y0);
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.tintColor = tintColor;
        this.radius = radius;
        this.opacity = opacity;
        this.bevel = Math.max(0.0F, bevel);
        this.specular = MathUtil.clamp(specular, 0.0F, 1.0F);
        this.dispersion = MathUtil.clamp(dispersion, 0.0F, 1.0F);
        this.frost = MathUtil.clamp(frost, 0.0F, 1.0F);
        this.lightX = MathUtil.clamp(lightX, -1.0F, 1.0F);
        this.lightY = MathUtil.clamp(lightY, -1.0F, 1.0F);
        this.textureSetup = TextureSetup.singleTexture(
                backdropView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        );
        GuiBackdrop.requestBlurRadius(blurRadiusPx);
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        float halfWidth = (this.x1 - this.x0) * 0.5F;
        float halfHeight = (this.y1 - this.y0) * 0.5F;
        int packedSizeX = UiVertexPacking.packSize(halfWidth * 2.0F);
        int packedSizeY = UiVertexPacking.packSize(halfHeight * 2.0F);
        int packedRadius = UiVertexPacking.packRadius(this.radius);
        int packedOpacity = MathUtil.clampByte(Math.round(this.opacity * 255.0F));
        // Opacity keeps the low byte the vertex shader has always read; frost
        // rides the high one.
        int packedEdge = UiVertexPacking.packU8Pair(
                packedOpacity,
                Math.round(this.frost * 255.0F)
        );
        // Bevel width and specular strength share one float, mirroring the
        // dual12 layout the rect pipeline uses for its bottom radii.
        float packedLiquid = UiVertexPacking.packDual12Raw(
                Math.round(this.bevel * UiVertexPacking.RADIUS_SCALE),
                Math.round(this.specular * SPECULAR_SCALE)
        );

        addVertex(vertexConsumer, this.x0, this.y0, -halfWidth, -halfHeight, packedSizeX, packedSizeY, packedRadius, packedEdge, packedLiquid);
        addVertex(vertexConsumer, this.x0, this.y1, -halfWidth, halfHeight, packedSizeX, packedSizeY, packedRadius, packedEdge, packedLiquid);
        addVertex(vertexConsumer, this.x1, this.y1, halfWidth, halfHeight, packedSizeX, packedSizeY, packedRadius, packedEdge, packedLiquid);
        addVertex(vertexConsumer, this.x1, this.y0, halfWidth, -halfHeight, packedSizeX, packedSizeY, packedRadius, packedEdge, packedLiquid);
    }

    private void addVertex(
            VertexConsumer vertexConsumer,
            float x,
            float y,
            float localX,
            float localY,
            int packedSizeX,
            int packedSizeY,
            int packedRadius,
            int packedEdge,
            float packedLiquid
    ) {
        vertexConsumer.addVertex(transformX(x, y), transformY(x, y), packedLiquid)
                .setColor(this.tintColor)
                .setUv(localX, localY)
                .setUv1(packedSizeX, packedSizeY)
                .setUv2(packedRadius, packedEdge)
                .setNormal(this.lightX, this.lightY, this.dispersion)
                .setLineWidth(0.0F);
    }

    @Override
    public RenderPipeline pipeline() {
        return GuiPipelines.BLUR_RECT;
    }

    @Override
    public TextureSetup textureSetup() {
        return this.textureSetup;
    }
}
