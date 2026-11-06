package win.furryhook.utils.render.gui;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import win.furryhook.context.MinecraftContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuiTexture.class);
    private static final Map<Identifier, GuiTexture> CACHE = new ConcurrentHashMap<>();

    /**
     * Variants an icon may keep at once.
     *
     * <p>Eight was not enough to hold one animation. {@link #quantize} used to round to whole
     * pixels, so an icon growing from sixteen to twenty-four on hover asked for nine different
     * sizes on the way up — one more than the cache could hold. The LRU then evicted on every
     * single frame, and an eviction here is not cheap bookkeeping: the miss behind it re-rasterises
     * the SVG, builds a fresh {@link net.minecraft.client.gui.components.DynamicTexture} and
     * registers a new GL texture. Per animating icon. Per frame.
     *
     * <p>With size bucketing below, the whole reachable range is a handful of buckets, so this is
     * now a ceiling that is never approached rather than a limit that thrashes.
     */
    private static final int MAX_VARIANTS = 32;

    /**
     * Icons are rasterised at bucketed sizes rather than at the exact pixel size asked for.
     *
     * <p>The size handed to {@link #textureSetup(float, float)} is derived from the element's
     * width times the GUI scale times the pose scale, so anything that animates — a hover grow, a
     * panel sliding open, a scale on the whole menu — changes it continuously. Rounding that to
     * whole pixels turns every frame of every animation into a distinct cache key, which is what
     * made a menu full of icons rasterise SVGs on the render thread all the way through an
     * animation and churn GL textures at frame rate.
     *
     * <p>Powers of two collapse that to at most a dozen buckets over the entire size range. An
     * icon drawn at 20px uses the 32px raster scaled down, which at {@link #SUPERSAMPLE} of two is
     * a 64px source — visually indistinguishable, and allocated once for the life of the session.
     */
    private static final int MIN_BUCKET = 16;

    /**
     * Kill switch for every {@code .svg} the interface draws — the menu icons, the header glyphs,
     * the HUD logo. Nothing is rasterised, no texture is created, the draw is dropped before it is
     * queued. Set it and the menu comes up bare; clear it and every icon is back, untouched.
     *
     * <p>It is a switch rather than a deletion on purpose. The measurements do not support blaming
     * the icons — the native leak ran at 128 MB/s before their rasterisation was bucketed and
     * 107 MB/s after, which is the same number twice — so throwing away forty-five icons would most
     * likely cost the look of the client and buy nothing. This way one launch answers the question
     * without losing any of that work.
     *
     * <p>Toggle from the game with {@code .icons off} / {@code .icons on}.
     */
    private static volatile boolean svgIconsDisabled;

    public static boolean svgIconsDisabled() {
        return svgIconsDisabled;
    }

    public static void setSvgIconsDisabled(boolean disabled) {
        svgIconsDisabled = disabled;
    }
    private static final int SUPERSAMPLE = 2;
    private static final int MAX_TEXTURE_SIZE = 2048;

    private final Identifier textureId;
    private final boolean svg;

    private volatile SVGDocument document;
    private TextureSetup staticSetup;
    private final LinkedHashMap<Long, TextureSetup> svgVariants = new LinkedHashMap<>(8, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TextureSetup> eldest) {
            if (size() <= MAX_VARIANTS) {
                return false;
            }
            MinecraftContext.mc.getTextureManager().release(variantId(GuiTexture.this.textureId, eldest.getKey()));
            return true;
        }
    };

    private GuiTexture(Identifier textureId) {
        this.textureId = textureId;
        this.svg = textureId.getPath().endsWith(".svg");
    }

    public static GuiTexture load(Identifier textureId) {
        return CACHE.computeIfAbsent(textureId, GuiTexture::new);
    }

    public static void prewarm(Collection<Identifier> ids) {
        Thread thread = new Thread(() -> {
            for (Identifier id : ids) {
                try {
                    GuiTexture texture = load(id);
                    if (texture.svg) {
                        texture.document();
                    }
                } catch (Throwable throwable) {

                    LOGGER.warn("Failed to prewarm GUI texture {}", id, throwable);
                }
            }
        }, "Blade SVG Prewarm");
        thread.setDaemon(true);
        thread.start();
    }

    public TextureSetup textureSetup(float deviceWidth, float deviceHeight) {
        if (!this.svg) {
            return staticSetup();
        }
        long key = packSize(quantize(deviceWidth), quantize(deviceHeight));
        TextureSetup cached = this.svgVariants.get(key);
        if (cached != null) {
            return cached;
        }
        TextureSetup created = createSvgSetup(key);
        this.svgVariants.put(key, created);
        return created;
    }

    public TextureSetup textureSetup() {
        if (!this.svg) {
            return staticSetup();
        }
        FloatSize size = documentSize();
        return textureSetup((float) size.getWidth(), (float) size.getHeight());
    }

    private TextureSetup staticSetup() {
        if (this.staticSetup == null) {
            DynamicTexture texture = new DynamicTexture(() -> this.textureId.toString(), readPng(this.textureId));
            texture.upload();
            MinecraftContext.mc.getTextureManager().register(this.textureId, texture);
            this.staticSetup = TextureSetup.singleTexture(
                    texture.getTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false)
            );
        }
        return this.staticSetup;
    }

    private TextureSetup createSvgSetup(long key) {
        int width = Math.min(MAX_TEXTURE_SIZE, unpackWidth(key) * SUPERSAMPLE);
        int height = Math.min(MAX_TEXTURE_SIZE, unpackHeight(key) * SUPERSAMPLE);
        Identifier variantId = variantId(this.textureId, key);
        DynamicTexture texture = new DynamicTexture(variantId::toString, rasterizeSvg(this.textureId, document(), width, height));
        texture.upload();
        MinecraftContext.mc.getTextureManager().register(variantId, texture);
        return TextureSetup.singleTexture(
                texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false)
        );
    }

    private SVGDocument document() {

        SVGDocument loaded = this.document;
        if (loaded == null) {
            loaded = loadDocument(this.textureId);
            this.document = loaded;
        }
        return loaded;
    }

    private FloatSize documentSize() {
        return document().size();
    }

    /**
     * Rounds a requested pixel size up to the next power of two, floored at {@link #MIN_BUCKET} and
     * capped at {@link #MAX_TEXTURE_SIZE} so the supersampled raster stays inside the texture limit.
     */
    private static int quantize(float devicePx) {
        int wanted = Math.max(MIN_BUCKET, (int) Math.ceil(devicePx));
        if (wanted >= MAX_TEXTURE_SIZE) {
            return MAX_TEXTURE_SIZE;
        }
        return Integer.highestOneBit(wanted - 1) << 1;
    }

    private static long packSize(int width, int height) {
        return ((long) width << 32) | (height & 0xFFFFFFFFL);
    }

    private static int unpackWidth(long key) {
        return (int) (key >>> 32);
    }

    private static int unpackHeight(long key) {
        return (int) key;
    }

    private static Identifier variantId(Identifier base, long key) {
        return base.withPath(path -> path + "/" + unpackWidth(key) + "x" + unpackHeight(key));
    }

    private static NativeImage readPng(Identifier textureId) {
        try (InputStream inputStream = MinecraftContext.mc.getResourceManager().open(textureId)) {
            return NativeImage.read(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read GUI texture: " + textureId, exception);
        }
    }

    private static SVGDocument loadDocument(Identifier textureId) {
        try (InputStream inputStream = MinecraftContext.mc.getResourceManager().open(textureId)) {
            URI documentUri = URI.create("resource://" + textureId.getNamespace() + "/" + textureId.getPath());
            SVGDocument document = new SVGLoader().load(inputStream, documentUri, LoaderContext.createDefault());
            if (document == null) {
                throw new IOException("Failed to parse SVG document: " + textureId);
            }
            return document;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read GUI texture: " + textureId, exception);
        }
    }

    private static NativeImage rasterizeSvg(Identifier textureId, SVGDocument document, int width, int height) {

        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = bufferedImage.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            document.render(null, graphics, new ViewBox(0, 0, width, height));
        } finally {
            graphics.dispose();
        }

        int clearBorder = textureId.getPath().contains("logo_boot") ? Math.max(1, Math.round(4.0F * width / 512.0F)) : 0;
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (clearBorder > 0 && (x < clearBorder || x >= width - clearBorder || y < clearBorder || y >= height - clearBorder)) {
                    nativeImage.setPixel(x, y, 0x00FFFFFF);
                    continue;
                }
                int argb = bufferedImage.getRGB(x, y);
                if (((argb >> 24) & 0xFF) == 0) {

                    argb = 0x00FFFFFF;
                }
                nativeImage.setPixel(x, y, argb);
            }
        }
        return nativeImage;
    }
}
