package win.furryhook.menu.core;

import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;

/**
 * Comet of accent light running clockwise around the menu's rounded border.
 *
 * The border is walked as one continuous path — four straight edges and four quarter-arcs,
 * laid out head to tail in clockwise order — so a single scalar position drives the whole
 * loop. The lit window is the last {@link #TRAIL_FRACTION} of the perimeter behind the head;
 * straight runs collapse into one gradient quad (the rect renderer interpolates the corner
 * alphas for free), corners are stamped as round dots because a quad cannot bend.
 *
 * Three stacked passes fake the bloom: a wide dim halo, a mid band, a thin hot core, plus a
 * shadowed blob at the head. No shader, no framebuffer.
 *
 * Phase is stepped by the menu's own frame delta, so the lap time stays FPS independent and
 * the comet freezes exactly when the menu stops rendering.
 */
public final class MenuBorderGlow {
    /** Seconds for one full lap of the border. */
    private static final float LOOP_SECONDS = 4.2F;
    /** Share of the perimeter that stays lit behind the head. */
    private static final float TRAIL_FRACTION = 0.17F;
    private static final float TRAIL_FALLOFF = 1.7F;

    // halo -> mid -> hot core. Thickness and arc step are reference px, scaled at draw time.
    private static final float[] PASS_THICKNESS = {13.0F, 6.0F, 2.0F};
    private static final float[] PASS_ALPHA = {0.10F, 0.24F, 1.0F};
    private static final float[] PASS_STEP = {5.0F, 3.0F, 1.5F};
    private static final float[] PASS_WHITEN = {0.0F, 0.12F, 0.45F};

    private static final float HEAD_SIZE = 3.4F;
    private static final float HEAD_GLOW = 15.0F;
    private static final float HEAD_WHITEN = 0.6F;
    private static final float HEAD_GLOW_ALPHA = 0.5F;

    // Path slots: top, TR, right, BR, bottom, BL, left, TL.
    private static final int SEGMENTS = 8;
    private static final boolean[] isArc = new boolean[SEGMENTS];
    private static final float[] segOffset = new float[SEGMENTS];
    private static final float[] segLength = new float[SEGMENTS];
    // Line: origin x, origin y, unit dir x, unit dir y. Arc: center x, center y, start angle, radius.
    private static final float[] segA = new float[SEGMENTS];
    private static final float[] segB = new float[SEGMENTS];
    private static final float[] segC = new float[SEGMENTS];
    private static final float[] segD = new float[SEGMENTS];

    private static float phase;

    private MenuBorderGlow() {
    }

    /**
     * @param radius corner radius already multiplied by the GUI scale
     * @param alpha  master fade, driven by the menu's open animation
     * @param delta  seconds since the previous menu frame
     */
    public static void render(float x, float y, float width, float height,
                              float radius, float scale, float alpha, float delta) {
        if (alpha <= 0.01F || width <= 0.0F || height <= 0.0F) {
            return;
        }
        float cornerRadius = Math.max(0.0F, Math.min(radius, Math.min(width, height) * 0.5F));
        float edgeX = Math.max(0.0F, width - cornerRadius * 2.0F);
        float edgeY = Math.max(0.0F, height - cornerRadius * 2.0F);
        float arc = (float) (Math.PI * cornerRadius * 0.5);
        float perimeter = (edgeX + edgeY) * 2.0F + arc * 4.0F;
        if (perimeter <= 1.0F) {
            return;
        }

        buildPath(x, y, width, height, cornerRadius, edgeX, edgeY, arc);

        phase += delta / LOOP_SECONDS;
        phase -= (float) Math.floor(phase);
        float head = phase * perimeter;
        float trail = perimeter * TRAIL_FRACTION;
        int accent = Theme.getAccent();

        for (int pass = 0; pass < PASS_THICKNESS.length; pass++) {
            float thickness = Math.max(0.5F, PASS_THICKNESS[pass] * scale);
            float step = Math.max(0.6F, PASS_STEP[pass] * scale);
            int color = ColorUtil.lerp(accent, ColorUtil.WHITE, PASS_WHITEN[pass]);
            float passAlpha = PASS_ALPHA[pass] * alpha;

            for (int segment = 0; segment < SEGMENTS; segment++) {
                if (segLength[segment] <= 0.001F) {
                    continue;
                }
                // The window wraps past the seam, so each segment is also tested one lap back.
                for (int lap = 0; lap <= 1; lap++) {
                    float base = segOffset[segment] - lap * perimeter;
                    float from = Math.max(base, head - trail);
                    float to = Math.min(base + segLength[segment], head);
                    if (to - from <= 0.001F) {
                        continue;
                    }
                    if (isArc[segment]) {
                        drawArcRun(segment, base, from - base, to - base, head, trail, thickness, step, color, passAlpha);
                    } else {
                        drawLineRun(segment, base, from - base, to - base, head, trail, thickness, color, passAlpha);
                    }
                }
            }
        }

        drawHead(head, accent, scale, alpha);
    }

    private static void drawHead(float head, int accent, float scale, float alpha) {
        int segment = segmentAt(head);
        if (segment < 0) {
            return;
        }
        float local = head - segOffset[segment];
        float size = HEAD_SIZE * scale;
        float headX = pointX(segment, local) - size / 2.0F;
        float headY = pointY(segment, local) - size / 2.0F;
        Render2DUtil.rect(headX, headY, size, size)
                .radius(size / 2.0F)
                .color(ColorUtil.withAlpha(ColorUtil.lerp(accent, ColorUtil.WHITE, HEAD_WHITEN), alpha))
                .shadow(ColorUtil.withAlpha(accent, HEAD_GLOW_ALPHA * alpha), HEAD_GLOW * scale)
                .draw();
    }

    /** One straight run of the trail: a single quad whose end alphas carry the falloff. */
    private static void drawLineRun(int segment, float base, float from, float to,
                                    float head, float trail, float thickness, int color, float passAlpha) {
        float ax = pointX(segment, from);
        float ay = pointY(segment, from);
        float bx = pointX(segment, to);
        float by = pointY(segment, to);
        int colorA = ColorUtil.withAlpha(color, passAlpha * intensity(head - (base + from), trail));
        int colorB = ColorUtil.withAlpha(color, passAlpha * intensity(head - (base + to), trail));

        if (segD[segment] == 0.0F) {
            boolean forward = ax <= bx;
            int left = forward ? colorA : colorB;
            int right = forward ? colorB : colorA;
            Render2DUtil.rect(Math.min(ax, bx), ay - thickness / 2.0F, Math.abs(bx - ax), thickness)
                    .color(left, right, right, left)
                    .draw();
        } else {
            boolean downward = ay <= by;
            int top = downward ? colorA : colorB;
            int bottom = downward ? colorB : colorA;
            Render2DUtil.rect(ax - thickness / 2.0F, Math.min(ay, by), thickness, Math.abs(by - ay))
                    .color(top, top, bottom, bottom)
                    .draw();
        }
    }

    /** Corner run: stamped as round dots, since a quad cannot follow the bend. */
    private static void drawArcRun(int segment, float base, float from, float to, float head, float trail,
                                   float thickness, float step, int color, float passAlpha) {
        int steps = Math.max(1, (int) Math.ceil((to - from) / step));
        for (int i = 0; i <= steps; i++) {
            float local = from + (to - from) * i / steps;
            float value = intensity(head - (base + local), trail);
            if (value <= 0.002F) {
                continue;
            }
            float dotX = pointX(segment, local) - thickness / 2.0F;
            float dotY = pointY(segment, local) - thickness / 2.0F;
            Render2DUtil.rect(dotX, dotY, thickness, thickness)
                    .radius(thickness / 2.0F)
                    .color(ColorUtil.withAlpha(color, passAlpha * value))
                    .draw();
        }
    }

    private static float intensity(float distanceBehindHead, float trail) {
        float remaining = 1.0F - distanceBehindHead / trail;
        return remaining <= 0.0F ? 0.0F : (float) Math.pow(remaining, TRAIL_FALLOFF);
    }

    private static int segmentAt(float position) {
        for (int segment = SEGMENTS - 1; segment >= 0; segment--) {
            if (segLength[segment] > 0.001F && position >= segOffset[segment]) {
                return segment;
            }
        }
        return -1;
    }

    private static float pointX(int segment, float local) {
        if (isArc[segment]) {
            return segA[segment] + segD[segment] * (float) Math.cos(segC[segment] + local / segD[segment]);
        }
        return segA[segment] + segC[segment] * local;
    }

    private static float pointY(int segment, float local) {
        if (isArc[segment]) {
            return segB[segment] + segD[segment] * (float) Math.sin(segC[segment] + local / segD[segment]);
        }
        return segB[segment] + segD[segment] * local;
    }

    /** Lays the border out clockwise from the top-left corner, straight run first. */
    private static void buildPath(float x, float y, float width, float height,
                                  float radius, float edgeX, float edgeY, float arc) {
        float right = x + width;
        float bottom = y + height;
        float offset = 0.0F;

        offset = line(0, offset, edgeX, x + radius, y, 1.0F, 0.0F);
        offset = curve(1, offset, arc, right - radius, y + radius, (float) (-Math.PI / 2.0), radius);
        offset = line(2, offset, edgeY, right, y + radius, 0.0F, 1.0F);
        offset = curve(3, offset, arc, right - radius, bottom - radius, 0.0F, radius);
        offset = line(4, offset, edgeX, right - radius, bottom, -1.0F, 0.0F);
        offset = curve(5, offset, arc, x + radius, bottom - radius, (float) (Math.PI / 2.0), radius);
        offset = line(6, offset, edgeY, x, bottom - radius, 0.0F, -1.0F);
        curve(7, offset, arc, x + radius, y + radius, (float) Math.PI, radius);
    }

    private static float line(int segment, float offset, float length,
                              float originX, float originY, float dirX, float dirY) {
        isArc[segment] = false;
        segOffset[segment] = offset;
        segLength[segment] = length;
        segA[segment] = originX;
        segB[segment] = originY;
        segC[segment] = dirX;
        segD[segment] = dirY;
        return offset + length;
    }

    private static float curve(int segment, float offset, float length,
                               float centerX, float centerY, float startAngle, float radius) {
        isArc[segment] = true;
        segOffset[segment] = offset;
        segLength[segment] = radius <= 0.001F ? 0.0F : length;
        segA[segment] = centerX;
        segB[segment] = centerY;
        segC[segment] = startAngle;
        segD[segment] = radius;
        return offset + segLength[segment];
    }
}
