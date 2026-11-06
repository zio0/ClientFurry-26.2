package win.furryhook.menu.core;

import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;

import java.util.Random;

/**
 * Ambient scene painted over the dimmed game and behind every menu panel: accent-tinted
 * light pooling along the bottom edge, and embers drifting up out of it.
 *
 * Both layers are pure quads — no shader, no framebuffer. Soft radial falloff is faked by
 * stacking gradient bands whose corner alphas follow a power curve, which the rect renderer
 * interpolates for free; an ember is three concentric rounded quads (halo, mid, hot core).
 *
 * State lives here and is stepped by the menu's own frame delta, so motion stays FPS
 * independent and pauses exactly when the menu stops rendering.
 */
public final class MenuBackgroundFx {
    private static final float TAU = (float) (Math.PI * 2.0);

    // ── light ────────────────────────────────────────────────────────────────
    private static final int GLOW_BANDS = 16;
    private static final float GLOW_FALLOFF = 2.3F;

    private static final float BASE_HEIGHT = 0.30F;
    private static final float BASE_ALPHA = 0.26F;

    private static final float DOME_HALF_WIDTH = 0.36F;
    private static final float DOME_HEIGHT = 0.52F;
    private static final float DOME_ALPHA = 0.20F;
    private static final float DOME_EDGE_FADE = 0.55F;

    private static final float POOL_HALF_WIDTH = 0.17F;
    private static final float POOL_HEIGHT = 0.28F;
    private static final float POOL_ALPHA = 0.13F;
    private static final float POOL_EDGE_FADE = 0.7F;

    // Slow, out-of-phase breathing so the three light sources never pulse together.
    private static final float BREATH_BASE_HZ = 0.11F;
    private static final float BREATH_DOME_HZ = 0.17F;
    private static final float BREATH_POOL_HZ = 0.13F;
    private static final float BREATH_DEPTH = 0.22F;

    // ── embers ───────────────────────────────────────────────────────────────
    private static final int MAX_EMBERS = 72;
    /** Spawns per second at a 1920px-wide screen; scaled linearly with width. */
    private static final float SPAWN_RATE = 13.0F;
    private static final int EMBER_WARM = 0xFFFFA55C;

    private static final float RISE_MIN = 26.0F;
    private static final float RISE_MAX = 78.0F;
    private static final float LIFE_MIN = 3.4F;
    private static final float LIFE_MAX = 8.5F;
    private static final float SIZE_MIN = 1.1F;
    private static final float SIZE_MAX = 3.4F;
    private static final float SWAY_MIN = 5.0F;
    private static final float SWAY_MAX = 26.0F;

    private static final Random RANDOM = new Random();
    private static final Ember[] EMBERS = new Ember[MAX_EMBERS];

    private static float time;
    private static float spawnAccumulator;

    static {
        for (int i = 0; i < EMBERS.length; i++) {
            EMBERS[i] = new Ember();
        }
    }

    private MenuBackgroundFx() {
    }

    /**
     * @param openT menu open/close easing, fades the whole layer in and out
     * @param scale panel scale, keeps ember size proportional to the GUI
     */
    public static void render(float screenWidth, float screenHeight, float delta, float openT,
                              float scale, boolean lightEnabled, boolean embersEnabled) {
        if (openT <= 0.01F || screenWidth <= 0.0F || screenHeight <= 0.0F) {
            return;
        }
        time += delta;
        int accent = Theme.getAccent();

        if (lightEnabled) {
            renderLight(screenWidth, screenHeight, openT, accent);
        }
        if (embersEnabled) {
            updateEmbers(screenWidth, screenHeight, delta);
            renderEmbers(openT, scale, accent);
        } else {
            releaseEmbers();
        }
    }

    /** Drops every live ember so re-enabling the layer starts clean instead of mid-flight. */
    public static void releaseEmbers() {
        for (Ember ember : EMBERS) {
            ember.alive = false;
        }
        spawnAccumulator = 0.0F;
    }

    // ── light ────────────────────────────────────────────────────────────────

    private static void renderLight(float screenWidth, float screenHeight, float openT, int accent) {
        float bottom = screenHeight;
        int rgb = accent & 0x00FFFFFF;

        // Full-width bed: the flat wash the rest of the light sits on.
        glow(screenWidth * 0.5F, bottom, screenWidth * 0.5F, screenHeight * BASE_HEIGHT,
                rgb, BASE_ALPHA * openT * breath(BREATH_BASE_HZ, 0.0F), 0.0F, 1.0F);

        // Centre dome: the actual source, widening as it climbs.
        glow(screenWidth * 0.5F, bottom, screenWidth * DOME_HALF_WIDTH, screenHeight * DOME_HEIGHT,
                rgb, DOME_ALPHA * openT * breath(BREATH_DOME_HZ, 1.7F), DOME_EDGE_FADE, 1.35F);

        // Two dimmer pools off to the sides, breathing against each other.
        float poolHalfWidth = screenWidth * POOL_HALF_WIDTH;
        float poolHeight = screenHeight * POOL_HEIGHT;
        glow(screenWidth * 0.17F, bottom, poolHalfWidth, poolHeight,
                rgb, POOL_ALPHA * openT * breath(BREATH_POOL_HZ, 0.0F), POOL_EDGE_FADE, 1.2F);
        glow(screenWidth * 0.83F, bottom, poolHalfWidth, poolHeight,
                rgb, POOL_ALPHA * openT * breath(BREATH_POOL_HZ, 3.1F), POOL_EDGE_FADE, 1.2F);
    }

    private static float breath(float hz, float phase) {
        return 1.0F - BREATH_DEPTH * 0.5F
                + BREATH_DEPTH * 0.5F * (float) Math.sin(time * hz * TAU + phase);
    }

    /**
     * Stacks {@link #GLOW_BANDS} gradient quads upwards from {@code bottomY}, each fading
     * vertically along a power curve and (when {@code edgeFade > 0}) horizontally out to
     * transparent at its own left/right edges. {@code spread} widens the upper bands so the
     * shape blooms outwards instead of standing as a column.
     */
    private static void glow(float centerX, float bottomY, float halfWidth, float height,
                             int rgb, float peakAlpha, float edgeFade, float spread) {
        if (peakAlpha <= 0.002F || halfWidth <= 0.0F || height <= 0.0F) {
            return;
        }
        for (int band = 0; band < GLOW_BANDS; band++) {
            float t0 = band / (float) GLOW_BANDS;
            float t1 = (band + 1) / (float) GLOW_BANDS;
            float alphaBottom = falloff(t0) * peakAlpha;
            float alphaTop = falloff(t1) * peakAlpha;
            if (alphaBottom <= 0.002F && alphaTop <= 0.002F) {
                continue;
            }

            float bandTop = bottomY - height * t1;
            float bandHeight = height * (t1 - t0);
            float bandHalf = halfWidth * (1.0F + (spread - 1.0F) * (t0 + t1) * 0.5F);

            if (edgeFade <= 0.001F) {
                Render2DUtil.rect(centerX - bandHalf, bandTop, bandHalf * 2.0F, bandHeight)
                        .color(ColorUtil.withAlpha(rgb, alphaTop), ColorUtil.withAlpha(rgb, alphaTop),
                                ColorUtil.withAlpha(rgb, alphaBottom), ColorUtil.withAlpha(rgb, alphaBottom))
                        .draw();
                continue;
            }

            // Solid middle, with a fading wing on each side: corner alphas carry both the
            // vertical curve and the horizontal fade at once.
            float fadeWidth = bandHalf * MathUtil.clamp(edgeFade, 0.0F, 1.0F);
            float coreHalf = bandHalf - fadeWidth;
            int topSolid = ColorUtil.withAlpha(rgb, alphaTop);
            int bottomSolid = ColorUtil.withAlpha(rgb, alphaBottom);
            int clear = rgb;

            if (coreHalf > 0.0F) {
                Render2DUtil.rect(centerX - coreHalf, bandTop, coreHalf * 2.0F, bandHeight)
                        .color(topSolid, topSolid, bottomSolid, bottomSolid)
                        .draw();
            }
            Render2DUtil.rect(centerX - bandHalf, bandTop, fadeWidth, bandHeight)
                    .color(clear, topSolid, bottomSolid, clear)
                    .draw();
            Render2DUtil.rect(centerX + coreHalf, bandTop, fadeWidth, bandHeight)
                    .color(topSolid, clear, clear, bottomSolid)
                    .draw();
        }
    }

    private static float falloff(float t) {
        return (float) Math.pow(1.0F - MathUtil.clamp(t, 0.0F, 1.0F), GLOW_FALLOFF);
    }

    // ── embers ───────────────────────────────────────────────────────────────

    private static void updateEmbers(float screenWidth, float screenHeight, float delta) {
        for (Ember ember : EMBERS) {
            if (!ember.alive) {
                continue;
            }
            ember.age += delta;
            ember.y -= ember.rise * delta;
            // Embers lose energy as they climb, so the top of the screen thins out.
            ember.rise = Math.max(ember.rise * (1.0F - 0.22F * delta), RISE_MIN * 0.4F);
            if (ember.age >= ember.life || ember.y < -ember.size * 4.0F) {
                ember.alive = false;
            }
        }

        spawnAccumulator += delta * SPAWN_RATE * (screenWidth / 1920.0F);
        int spawns = (int) spawnAccumulator;
        spawnAccumulator -= spawns;
        for (int i = 0; i < spawns; i++) {
            spawn(screenWidth, screenHeight);
        }
    }

    private static void spawn(float screenWidth, float screenHeight) {
        for (Ember ember : EMBERS) {
            if (ember.alive) {
                continue;
            }
            ember.alive = true;
            ember.age = 0.0F;
            ember.life = range(LIFE_MIN, LIFE_MAX);
            // Biased towards the middle, where the dome light is, but never only there.
            float centered = (RANDOM.nextFloat() + RANDOM.nextFloat()) * 0.5F;
            ember.originX = screenWidth * MathUtil.clamp(centered + range(-0.22F, 0.22F), -0.05F, 1.05F);
            ember.y = screenHeight + range(0.0F, screenHeight * 0.12F);
            ember.rise = range(RISE_MIN, RISE_MAX);
            ember.size = range(SIZE_MIN, SIZE_MAX);
            ember.swayAmplitude = range(SWAY_MIN, SWAY_MAX);
            ember.swayFrequency = range(0.15F, 0.55F);
            ember.swayPhase = range(0.0F, TAU);
            ember.flickerFrequency = range(1.6F, 5.4F);
            ember.flickerPhase = range(0.0F, TAU);
            ember.warmth = RANDOM.nextFloat() * RANDOM.nextFloat();
            // Roughly a third of embers drift in the theme accent colour.
            ember.accentTinted = RANDOM.nextInt(3) == 0;
            return;
        }
    }

    private static void renderEmbers(float openT, float scale, int accent) {
        for (Ember ember : EMBERS) {
            if (!ember.alive) {
                continue;
            }
            float t = ember.age / ember.life;
            float fadeIn = MathUtil.clamp(t / 0.12F, 0.0F, 1.0F);
            float fadeOut = MathUtil.clamp((1.0F - t) / 0.4F, 0.0F, 1.0F);
            float flicker = 0.62F + 0.38F * (float) Math.sin(time * ember.flickerFrequency + ember.flickerPhase);
            float alpha = fadeIn * fadeOut * flicker * openT;
            if (alpha <= 0.004F) {
                continue;
            }

            float x = ember.originX
                    + (float) Math.sin(time * ember.swayFrequency * TAU + ember.swayPhase)
                    * ember.swayAmplitude * scale;
            float y = ember.y;
            float size = ember.size * scale;

            // A share of embers take the theme accent instead of the warm amber, so the
            // rising field gently echoes whatever accent mode is running.
            int warmthBase = ember.accentTinted
                    ? ColorUtil.lerp(accent, ColorUtil.rgb(255, 255, 255), 0.25F)
                    : ColorUtil.lerp(accent, EMBER_WARM, ember.warmth);
            int base = warmthBase & 0x00FFFFFF;
            int core = ColorUtil.lerp(base | 0xFF000000, ColorUtil.WHITE, 0.55F) & 0x00FFFFFF;

            float halo = size * 4.2F;
            Render2DUtil.rect(x - halo * 0.5F, y - halo * 0.5F, halo, halo)
                    .color(ColorUtil.withAlpha(base, 0.10F * alpha))
                    .radius(halo * 0.5F)
                    .draw();
            float mid = size * 2.1F;
            Render2DUtil.rect(x - mid * 0.5F, y - mid * 0.5F, mid, mid)
                    .color(ColorUtil.withAlpha(base, 0.28F * alpha))
                    .radius(mid * 0.5F)
                    .draw();
            Render2DUtil.rect(x - size * 0.5F, y - size * 0.5F, size, size)
                    .color(ColorUtil.withAlpha(core, 0.92F * alpha))
                    .radius(size * 0.5F)
                    .draw();
        }
    }

    private static float range(float min, float max) {
        return min + RANDOM.nextFloat() * (max - min);
    }

    private static final class Ember {
        private boolean alive;
        private float originX;
        private float y;
        private float rise;
        private float age;
        private float life;
        private float size;
        private float swayAmplitude;
        private float swayFrequency;
        private float swayPhase;
        private float flickerFrequency;
        private float flickerPhase;
        private float warmth;
        private boolean accentTinted;
    }
}
