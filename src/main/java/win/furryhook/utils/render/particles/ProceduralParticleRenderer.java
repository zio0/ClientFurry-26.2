package win.furryhook.utils.render.particles;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.post.EmissiveBloom;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.Render3DUtil;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;

public final class ProceduralParticleRenderer {
    private static final int INSTANCE_STRIDE = 16 * Float.BYTES;
    private static final int UNIFORM_SIZE = new Std140SizeCalculator()
            .putVec4()
            .putVec4()
            .putVec4()
            .get();
    /**
     * How far past the sprite the quad is grown so the halo has room. Without the
     * padding the falloff is cut off flat at the sprite border and the particle
     * reads as a decal instead of as a light.
     */
    private static final float BLOOM_QUAD_PAD = 2.6F;
    private static final float PLAIN_QUAD_PAD = 1.45F;
    private static final GpuFormat EMISSIVE_FORMAT = EmissiveBloom.FORMAT;

    private static final VertexFormat INSTANCE_FORMAT = VertexFormat.builder(1)
            .addAttribute("PosSize", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Dynamics", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Params", GpuFormat.RGBA32_FLOAT)
            .build();

    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withSampler("DepthSampler")
            .withUniform("ProceduralParticleUniforms", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.parse("furryhook:pipeline/world/procedural_particles"))
            .withVertexShader(Identifier.parse("furryhook:core/procedural_particle"))
            .withFragmentShader(Identifier.parse("furryhook:core/procedural_particle"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(
                    new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)
            ))
            .withDepthStencilState(new DepthStencilState(
                    CompareOp.GREATER_THAN_OR_EQUAL,
                    false
            ))
            .withVertexBinding(0, INSTANCE_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();

    /**
     * Same shader, float target, pure additive, and no depth attachment - the
     * occlusion is done in the fragment shader against the copied scene depth, so
     * this pass can run at half resolution against a buffer that has none.
     */
    private static final RenderPipeline EMISSIVE_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.parse("furryhook:pipeline/world/procedural_particles_emissive"))
            .withVertexShader(Identifier.parse("furryhook:core/procedural_particle"))
            .withFragmentShader(Identifier.parse("furryhook:core/procedural_particle"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(LAYOUT)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)),
                    EMISSIVE_FORMAT,
                    ColorTargetState.WRITE_ALL
            ))
            .withDepthStencilState(Optional.<DepthStencilState>empty())
            .withVertexBinding(0, INSTANCE_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();

    private final EmissiveBloom emissiveBloom = new EmissiveBloom("furryhook-particles");
    private TextureTarget depthCopy;

    public record Sprite(
            Vec3 position,
            float halfSize,
            int color,
            Shape shape,
            float rotation,
            float normalizedLife,
            float seed,
            float phase
    ) {
    }

    /**
     * @param intensity  how much of the blurred light is added back to the scene
     * @param radius     kawase mip count; each level roughly doubles the spread
     * @param boost      HDR gain written into the float buffer before blurring
     * @param anamorphic weight of the horizontal streak on top of the round bloom
     * @param birthFlash strength of the spawn punch, 0 disables it
     */
    public record Bloom(
            boolean enabled,
            float intensity,
            int radius,
            float boost,
            float anamorphic,
            float birthFlash,
            float chromatic
    ) {
        public static final Bloom OFF = new Bloom(false, 0.0F, 1, 1.0F, 0.0F, 0.0F, 0.0F);

        public EmissiveBloom.Settings toSettings() {
            return new EmissiveBloom.Settings(
                    this.enabled,
                    this.intensity,
                    this.radius,
                    this.boost,
                    this.anamorphic
            );
        }
    }

    public static RenderPipeline pipeline() {
        return PIPELINE;
    }

    public static RenderPipeline[] pipelines() {
        return new RenderPipeline[]{PIPELINE, EMISSIVE_PIPELINE};
    }

    public enum Shape {
        RANDOM("Random", -1, 0.4F, 0.5F),
        STAR("Stars", 0, 0.5F, 0.65F),
        DOLLAR("Dollars", 1, 0.3F, 0.2F),
        SNOWFLAKE("Snowflakes", 2, 0.25F, 0.15F),
        GLOW("Glow", 3, 1.0F, 0.9F),
        PUMPKIN("Pumpkins", 4, 0.2F, 0.0F),
        HEART("Hearts", 5, 0.35F, 0.2F),
        SPARK("Sparks", 6, 0.8F, 0.85F),
        EMBER("Embers", 7, 0.9F, 0.8F);

        private static final Shape[] CONCRETE = {
                STAR, DOLLAR, SNOWFLAKE, GLOW, PUMPKIN, HEART, SPARK, EMBER
        };

        private final String displayName;
        private final int shapeId;
        private final float additivity;
        private final float glow;

        Shape(String displayName, int shapeId, float additivity, float glow) {
            this.displayName = displayName;
            this.shapeId = shapeId;
            this.additivity = additivity;
            this.glow = glow;
        }

        public String displayName() {
            return this.displayName;
        }

        public Shape resolve(Random random) {
            return this == RANDOM ? CONCRETE[random.nextInt(CONCRETE.length)] : this;
        }

        public static Shape fromDisplayName(String value) {
            for (Shape shape : values()) {
                if (shape.displayName.equals(value)) {
                    return shape;
                }
            }
            return RANDOM;
        }

        public static String[] displayNames() {
            Shape[] values = values();
            String[] names = new String[values.length];
            for (int index = 0; index < values.length; index++) {
                names[index] = values[index].displayName;
            }
            return names;
        }
    }

    public void render(List<Sprite> sprites, float glow, boolean natural, Bloom bloom) {
        Minecraft minecraft = Minecraft.getInstance();
        if (sprites.isEmpty() || minecraft.level == null) {
            return;
        }

        var target = minecraft.gameRenderer.mainRenderTarget();
        if (target == null
                || target.getColorTextureView() == null
                || target.getDepthTexture() == null
                || target.getDepthTextureView() == null
                || target.width <= 0
                || target.height <= 0) {
            return;
        }

        ensureDepthCopy(target.width, target.height);
        if (this.depthCopy == null
                || this.depthCopy.getDepthTexture() == null
                || this.depthCopy.getDepthTextureView() == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                target.getDepthTexture(),
                this.depthCopy.getDepthTexture(),
                0, 0, 0, 0, 0,
                target.width,
                target.height
        );

        float clampedGlow = Math.clamp(glow, 0.0F, 1.0F);
        boolean useBloom = bloom.enabled() && bloom.intensity() > 0.001F;
        float quadPad = useBloom ? BLOOM_QUAD_PAD : PLAIN_QUAD_PAD;

        ByteBuffer instances = buildInstances(minecraft, sprites, quadPad);
        GpuBuffer instanceBuffer = null;
        GpuBuffer sceneUniforms = null;
        GpuBuffer emissiveUniforms = null;
        try {
            instanceBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Blade Procedural Particle Instances",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    instances
            );
            sceneUniforms = uploadUniforms(
                    target.width,
                    target.height,
                    clampedGlow,
                    natural,
                    false,
                    bloom
            );

            GpuSampler depthSampler = RenderSystem.getSamplerCache()
                    .getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Blade Procedural Particles",
                    target.getColorTextureView(),
                    Optional.empty(),
                    target.getDepthTextureView(),
                    OptionalDouble.empty()
            )) {
                drawInstances(pass, PIPELINE, sceneUniforms, depthSampler, instanceBuffer, sprites.size());
            }

            if (!useBloom) {
                return;
            }

            TextureTarget emissiveTarget = this.emissiveBloom.begin(target.width, target.height);
            if (emissiveTarget == null) {
                return;
            }

            emissiveUniforms = uploadUniforms(
                    emissiveTarget.width,
                    emissiveTarget.height,
                    clampedGlow,
                    natural,
                    true,
                    bloom
            );
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Blade Procedural Particle Emissive",
                    emissiveTarget.getColorTextureView(),
                    Optional.empty()
            )) {
                drawInstances(pass, EMISSIVE_PIPELINE, emissiveUniforms, depthSampler, instanceBuffer, sprites.size());
            }

            this.emissiveBloom.composite(target.getColorTextureView(), bloom.toSettings());
        } finally {
            MemoryUtil.memFree(instances);
            if (sceneUniforms != null) {
                sceneUniforms.close();
            }
            if (emissiveUniforms != null) {
                emissiveUniforms.close();
            }
            if (instanceBuffer != null) {
                instanceBuffer.close();
            }
        }
    }

    private void drawInstances(RenderPass pass,
                               RenderPipeline pipeline,
                               GpuBuffer uniforms,
                               GpuSampler depthSampler,
                               GpuBuffer instanceBuffer,
                               int instanceCount) {
        pass.setPipeline(pipeline);
        pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
        pass.setUniform("ProceduralParticleUniforms", uniforms);
        pass.bindTexture("DepthSampler", this.depthCopy.getDepthTextureView(), depthSampler);
        pass.setVertexBuffer(0, instanceBuffer.slice());
        pass.draw(6, instanceCount, 0, 0);
    }

    private ByteBuffer buildInstances(Minecraft minecraft, List<Sprite> sprites, float quadPad) {
        ByteBuffer data = MemoryUtil.memAlloc(sprites.size() * INSTANCE_STRIDE)
                .order(ByteOrder.nativeOrder());
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 cameraPosition = camera.position();
        Matrix4f viewPose = Render3DUtil.cameraViewPose(camera);
        for (Sprite sprite : sprites) {
            Vector4f position = Render3DUtil.toViewSpace(
                    sprite.position(),
                    cameraPosition,
                    viewPose
            );
            Shape shape = sprite.shape() == Shape.RANDOM ? Shape.STAR : sprite.shape();
            putVec4(data, position.x, position.y, position.z, sprite.halfSize());
            putVec4(
                    data,
                    sprite.rotation(),
                    Math.clamp(sprite.normalizedLife(), 0.0F, 1.0F),
                    sprite.seed(),
                    sprite.phase()
            );
            int color = sprite.color();
            putVec4(
                    data,
                    ColorUtil.red(color) / 255.0F,
                    ColorUtil.green(color) / 255.0F,
                    ColorUtil.blue(color) / 255.0F,
                    ColorUtil.alpha(color) / 255.0F
            );
            putVec4(data, shape.shapeId, shape.additivity, shape.glow, quadPad);
        }
        return data.flip();
    }

    private GpuBuffer uploadUniforms(int width,
                                     int height,
                                     float glow,
                                     boolean natural,
                                     boolean emissivePass,
                                     Bloom bloom) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "Blade Procedural Particle UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                UNIFORM_SIZE
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                    .putVec4(PostFx.shaderTime(), glow, natural ? 1.0F : 0.0F, 0.0025F)
                    .putVec4(
                            width,
                            height,
                            1.0F,
                            0.0F
                    )
                    .putVec4(
                            emissivePass ? 1.0F : 0.0F,
                            Math.max(0.0F, bloom.boost()),
                            Math.max(0.0F, bloom.birthFlash()),
                            Math.clamp(bloom.chromatic(), 0.0F, 1.0F)
                    )
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
        }
        return buffer;
    }

    private void ensureDepthCopy(int width, int height) {
        if (this.depthCopy != null
                && this.depthCopy.width == width
                && this.depthCopy.height == height) {
            return;
        }
        if (this.depthCopy != null) {
            this.depthCopy.destroyBuffers();
        }
        this.depthCopy = new TextureTarget(
                "furryhook-procedural-particle-depth",
                width,
                height,
                true,
                GpuFormat.RGBA8_UNORM
        );
    }

    private static void putVec4(ByteBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }

    public void release() {
        if (this.depthCopy != null) {
            this.depthCopy.destroyBuffers();
            this.depthCopy = null;
        }
        this.emissiveBloom.release();
    }
}
