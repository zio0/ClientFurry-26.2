package win.furryhook.utils.render.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import win.furryhook.context.RenderContext;
import win.furryhook.utils.math.MathUtil;

/**
 * Circular progress ring, ported from divine's health-ring widget (used by TargetHudElement).
 * Divine backs this with a dedicated SDF fragment shader; furryhook's UI vertex format has no
 * spare channels for an arc SDF, so this approximates the ring with many small rotated
 * {@link Render2DUtil.RectBuilder} segments driven through the existing GUI pose stack instead
 * of adding a new pipeline/vertex-format for a single widget.
 */
public final class RingProgress {
    private static final int MAX_SEGMENTS = 64;

    private RingProgress() {
    }

    /**
     * @param centerX          ring center, screen space
     * @param centerY          ring center, screen space
     * @param diameter         outer diameter in pixels
     * @param progress         fill fraction, 0..1, starting at 12 o'clock going clockwise
     * @param thicknessFraction ring stroke thickness as a fraction of the diameter (divine uses ~0.12-0.13)
     * @param color            ARGB stroke color
     */
    public static void draw(float centerX, float centerY, float diameter, float progress, float thicknessFraction, int color) {
        if (diameter <= 0.0F || ((color >>> 24) & 0xFF) == 0) {
            return;
        }
        float clampedProgress = MathUtil.clamp01(progress);
        if (clampedProgress <= 0.0005F) {
            return;
        }
        GuiGraphicsExtractor extractor = RenderContext.currentGuiGraphicsExtractor();
        if (extractor == null) {
            return;
        }

        float radius = diameter * 0.5F;
        float thickness = Math.max(1.0F, thicknessFraction * diameter);

        float sweepDeg = clampedProgress * 360.0F;
        int segments = Math.max(1, Math.round(MAX_SEGMENTS * clampedProgress));
        float stepDeg = sweepDeg / segments;
        // Slight overlap between neighboring segments hides the seam between rounded caps.
        float segArcDeg = Math.min(360.0F, stepDeg + 1.2F);
        float chordLength = 2.0F * radius * (float) Math.sin(Math.toRadians(segArcDeg * 0.5F));
        float capRadius = Math.min(thickness, chordLength) * 0.5F;

        var pose = extractor.pose();
        for (int i = 0; i < segments; i++) {
            float angleDeg = -90.0F + stepDeg * i;
            pose.pushMatrix();
            pose.translate(centerX, centerY);
            pose.rotate((float) Math.toRadians(angleDeg));
            Render2DUtil.rect(-chordLength * 0.5F, -radius, chordLength, thickness)
                    .color(color)
                    .radius(capRadius)
                    .draw();
            pose.popMatrix();
        }
    }
}
