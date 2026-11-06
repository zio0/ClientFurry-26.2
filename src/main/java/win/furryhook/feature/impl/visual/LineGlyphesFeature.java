package win.furryhook.feature.impl.visual;

import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.post.EmissiveBloom;
import win.furryhook.utils.render.world.GlowStripRenderer;
import win.furryhook.utils.render.world.GlowStripRenderer.Point;
import win.furryhook.utils.render.world.GlowStripRenderer.Strip;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Glowing lines that run through the world, weaving side to side and climbing as
 * they go. Same light and bloom path as the particles and Trails.
 */
public final class LineGlyphesFeature extends Feature implements MinecraftContext {
    private static final int SUBDIVISIONS = 3;
    private static final int SPAWN_ATTEMPTS = 4;
    private static final float TAIL_FADE = 0.3F;
    private static final float FADE_OUT_START = 0.75F;
    private static final double TWO_PI = 6.283185307179586D;

    public final NumberSetting count = register(new NumberSetting(
            "Count", 8.0D, 1.0D, 32.0D, 1.0D, ""
    ).configKey("render.lineglyphes.count"));
    public final NumberSetting radius = register(new NumberSetting(
            "Radius", 16.0D, 3.0D, 48.0D, 1.0D, " blocks"
    ).configKey("render.lineglyphes.radius"));
    public final NumberSetting speed = register(new NumberSetting(
            "Speed", 0.25D, 0.05D, 1.0D, 0.05D, ""
    ).configKey("render.lineglyphes.speed"));
    public final NumberSetting climb = register(new NumberSetting(
            "Climb", 0.35D, 0.0D, 1.5D, 0.05D, ""
    ).configKey("render.lineglyphes.climb"));
    public final NumberSetting length = register(new NumberSetting(
            "Length", 40.0D, 6.0D, 120.0D, 1.0D, " points"
    ).configKey("render.lineglyphes.length"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.lineglyphes.colormode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color", 0xFFB07CFF
    ).configKey("render.lineglyphes.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced", false
    ).asExpander().configKey("render.lineglyphes.advanced"));

    public final NumberSetting wiggle = register(new NumberSetting(
            "Wiggle", 0.5D, 0.0D, 2.0D, 0.05D, ""
    ).configKey("render.lineglyphes.wiggle").advancedOnly());
    public final NumberSetting wiggleSpeed = register(new NumberSetting(
            "Wiggle Speed", 0.35D, 0.05D, 1.5D, 0.05D, ""
    ).configKey("render.lineglyphes.wigglespeed").advancedOnly());
    public final NumberSetting lifetime = register(new NumberSetting(
            "Lifetime", 6000.0D, 800.0D, 20000.0D, 100.0D, " ms"
    ).configKey("render.lineglyphes.lifetime").advancedOnly());
    public final NumberSetting width = register(new NumberSetting(
            "Line Width", 0.06D, 0.01D, 0.4D, 0.01D, " blocks"
    ).configKey("render.lineglyphes.width").advancedOnly());
    public final NumberSetting glow = register(new NumberSetting(
            "Glow", 1.0D, 0.0D, 2.5D, 0.05D, ""
    ).configKey("render.lineglyphes.glow").advancedOnly());
    public final NumberSetting coreWidth = register(new NumberSetting(
            "Core", 0.3D, 0.05D, 1.0D, 0.05D, ""
    ).configKey("render.lineglyphes.core").advancedOnly());
    public final NumberSetting flowSpeed = register(new NumberSetting(
            "Flow Speed", 0.8D, 0.0D, 5.0D, 0.1D, ""
    ).configKey("render.lineglyphes.flowspeed").advancedOnly());
    public final NumberSetting chromatic = register(new NumberSetting(
            "Chromatic", 0.5D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.lineglyphes.chromatic").advancedOnly());

    public final BooleanSetting bloom = register(new BooleanSetting(
            "Bloom", true
    ).configKey("render.lineglyphes.bloom").advancedOnly());
    public final NumberSetting bloomIntensity = register(new NumberSetting(
            "Bloom Intensity", 1.2D, 0.0D, 3.0D, 0.05D, ""
    ).configKey("render.lineglyphes.bloomintensity")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting bloomRadius = register(new NumberSetting(
            "Bloom Radius", 3.0D, 1.0D, 4.0D, 1.0D, ""
    ).configKey("render.lineglyphes.bloomradius")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting hdrBoost = register(new NumberSetting(
            "HDR Boost", 2.5D, 0.5D, 6.0D, 0.1D, ""
    ).configKey("render.lineglyphes.hdrboost")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting anamorphic = register(new NumberSetting(
            "Anamorphic", 0.4D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.lineglyphes.anamorphic")
            .visibleWhen(this.bloom::getValue).advancedOnly());

    private final List<Snake> snakes = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>();
    private final GlowStripRenderer renderer = new GlowStripRenderer("furryhook-lineglyphes");
    private final Random random = new Random();

    public LineGlyphesFeature() {
        super(
                "LineGlyphes",
                "Glowing lines running through the world, weaving and climbing",
                FeatureCategory.VISUAL,
                BindSetting.UNBOUND
        );
    }

    public static LineGlyphesFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(LineGlyphesFeature.class);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.snakes.clear();
            return;
        }

        long now = System.currentTimeMillis();
        double stepSize = this.speed.getValue();
        double climbRate = this.climb.getValue();
        double turnRate = this.wiggle.getValue() * 0.35D;
        double wigglePhaseStep = this.wiggleSpeed.getValue();
        int maxPoints = this.length.getValue().intValue();
        double keepDistanceSqr = Math.pow(this.radius.getValue() * 2.0D, 2.0D);
        Vec3 eye = mc.player.getEyePosition();

        Iterator<Snake> iterator = this.snakes.iterator();
        while (iterator.hasNext()) {
            Snake snake = iterator.next();
            if (snake.finished(now) || snake.head().distanceToSqr(eye) > keepDistanceSqr) {
                iterator.remove();
                continue;
            }
            snake.advance(stepSize, climbRate, turnRate, wigglePhaseStep, maxPoints);
        }

        int wanted = this.count.getValue().intValue();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS && this.snakes.size() < wanted; attempt++) {
            this.snakes.add(createSnake(now));
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        this.snakes.clear();
    }

    @Override
    protected void onDisable() {
        this.snakes.clear();
        this.strips.clear();
        this.renderer.release();
    }

    public void renderWorld() {
        if (mc.player == null || mc.level == null || this.snakes.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        int baseColor = ColorMode.resolve(this.colorMode, this.color);
        float lineWidth = this.width.getValue().floatValue();

        this.strips.clear();
        for (Snake snake : this.snakes) {
            if (snake.points.size() < 2) {
                continue;
            }
            float progress = snake.progress(now);
            float alpha = progress <= FADE_OUT_START
                    ? 1.0F
                    : 1.0F - smoothstep((progress - FADE_OUT_START) / (1.0F - FADE_OUT_START));
            if (alpha <= 0.01F) {
                continue;
            }

            List<Vec3> path = GlowStripRenderer.smooth(snake.points, SUBDIVISIONS);
            List<Point> points = new ArrayList<>(path.size());
            for (int index = 0; index < path.size(); index++) {
                float position = (float) index / (path.size() - 1);
                points.add(new Point(
                        path.get(index),
                        lineWidth * (TAIL_FADE + (1.0F - TAIL_FADE) * position),
                        ColorUtil.multiplyAlpha(baseColor, alpha * tailFade(position))
                ));
            }
            this.strips.add(new Strip(points));
        }

        if (this.strips.isEmpty()) {
            return;
        }
        this.renderer.render(
                this.strips,
                new GlowStripRenderer.Style(
                        this.glow.getValue().floatValue(),
                        this.coreWidth.getValue().floatValue(),
                        this.chromatic.getValue().floatValue(),
                        this.flowSpeed.getValue().floatValue(),
                        0.004F,
                        this.hdrBoost.getValue().floatValue()
                ),
                currentBloom()
        );
    }

    private EmissiveBloom.Settings currentBloom() {
        if (!this.bloom.getValue()) {
            return EmissiveBloom.Settings.OFF;
        }
        return new EmissiveBloom.Settings(
                true,
                this.bloomIntensity.getValue().floatValue(),
                this.bloomRadius.getValue().intValue(),
                this.hdrBoost.getValue().floatValue(),
                this.anamorphic.getValue().floatValue()
        );
    }

    private Snake createSnake(long now) {
        double spawnRadius = this.radius.getValue();
        double angle = this.random.nextDouble() * TWO_PI;
        double distance = spawnRadius * (0.3D + 0.7D * this.random.nextDouble());
        // Starts low so the climb has somewhere to go; the line is what is being
        // watched, so it is allowed to run straight through terrain.
        Vec3 start = mc.player.position().add(
                Math.cos(angle) * distance,
                -1.0D + this.random.nextDouble() * 2.0D,
                Math.sin(angle) * distance
        );
        return new Snake(
                start,
                this.random.nextDouble() * TWO_PI,
                this.random.nextDouble() * TWO_PI,
                this.random.nextBoolean() ? 1.0D : -1.0D,
                now,
                this.lifetime.getValue().floatValue()
        );
    }

    private static float tailFade(float position) {
        return Math.clamp(position * 2.2F, 0.0F, 1.0F);
    }

    private static float smoothstep(float value) {
        float clamped = Math.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    /**
     * A head that walks forward, swings its heading with a sine, and rises as it
     * goes; the body is the trail of positions it has already been through.
     */
    private static final class Snake {
        private final List<Vec3> points = new ArrayList<>();
        private final long createdAt;
        private final float lifetimeMs;
        private final double turnDirection;
        private double heading;
        private double phase;

        private Snake(Vec3 start,
                      double heading,
                      double phase,
                      double turnDirection,
                      long createdAt,
                      float lifetimeMs) {
            this.points.add(start);
            this.heading = heading;
            this.phase = phase;
            this.turnDirection = turnDirection;
            this.createdAt = createdAt;
            this.lifetimeMs = lifetimeMs;
        }

        private Vec3 head() {
            return this.points.getLast();
        }

        private void advance(double stepSize,
                             double climbRate,
                             double turnRate,
                             double phaseStep,
                             int maxPoints) {
            this.phase += phaseStep;
            this.heading += Math.sin(this.phase) * turnRate * this.turnDirection;

            // The climb pulses with the same phase, so the line lifts in waves
            // rather than sliding up a ramp.
            double rise = climbRate * stepSize * (0.55D + 0.45D * Math.sin(this.phase * 0.5D));
            Vec3 head = head();
            this.points.add(new Vec3(
                    head.x + Math.cos(this.heading) * stepSize,
                    head.y + rise,
                    head.z + Math.sin(this.heading) * stepSize
            ));
            while (this.points.size() > Math.max(2, maxPoints)) {
                this.points.removeFirst();
            }
        }

        private float progress(long now) {
            return Math.clamp((now - this.createdAt) / this.lifetimeMs, 0.0F, 1.0F);
        }

        private boolean finished(long now) {
            return now - this.createdAt >= this.lifetimeMs;
        }
    }
}
