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
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.post.EmissiveBloom;
import win.furryhook.utils.render.post.PostFx;
import win.furryhook.utils.render.world.GlowStripRenderer;
import win.furryhook.utils.render.world.GlowStripRenderer.Point;
import win.furryhook.utils.render.world.GlowStripRenderer.Strip;

import java.util.ArrayList;
import java.util.List;

/**
 * Three ribbons winding behind the player, lit by the same emissive-plus-bloom
 * path as the procedural particles.
 */
public final class TrailsFeature extends Feature implements MinecraftContext {
    private static final int RIBBONS = 3;
    /** Catmull-Rom points inserted between two tick samples. */
    private static final int SUBDIVISIONS = 4;
    private static final double MIN_STEP_SQR = 0.0004D;
    /** Beyond this the player teleported, and interpolating across it draws a streak through the world. */
    private static final double TELEPORT_DISTANCE_SQR = 64.0D;
    private static final float TAIL_WIDTH = 0.18F;
    private static final double TWO_PI = 6.283185307179586D;

    public final NumberSetting length = register(new NumberSetting(
            "Length", 30.0D, 6.0D, 90.0D, 1.0D, " points"
    ).configKey("render.trails.length"));
    public final NumberSetting width = register(new NumberSetting(
            "Width", 0.12D, 0.02D, 0.6D, 0.01D, " blocks"
    ).configKey("render.trails.width"));
    public final NumberSetting spread = register(new NumberSetting(
            "Spread", 0.25D, 0.0D, 1.5D, 0.05D, " blocks"
    ).configKey("render.trails.spread"));
    public final NumberSetting height = register(new NumberSetting(
            "Height", 1.0D, 0.0D, 2.5D, 0.05D, " blocks"
    ).configKey("render.trails.height"));
    public final BooleanSetting thirdPersonOnly = register(new BooleanSetting(
            "Third Person Only", true
    ).configKey("render.trails.thirdperson"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.trails.colormode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color", 0xFF6BE1FF
    ).configKey("render.trails.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced", false
    ).asExpander().configKey("render.trails.advanced"));

    public final NumberSetting wave = register(new NumberSetting(
            "Wave", 0.18D, 0.0D, 1.0D, 0.01D, " blocks"
    ).configKey("render.trails.wave").advancedOnly());
    public final NumberSetting waveSpeed = register(new NumberSetting(
            "Wave Speed", 1.5D, 0.0D, 6.0D, 0.1D, ""
    ).configKey("render.trails.wavespeed").advancedOnly());
    public final NumberSetting waveDensity = register(new NumberSetting(
            "Wave Density", 0.35D, 0.05D, 1.5D, 0.05D, ""
    ).configKey("render.trails.wavedensity").advancedOnly());
    public final NumberSetting glow = register(new NumberSetting(
            "Glow", 1.0D, 0.0D, 2.5D, 0.05D, ""
    ).configKey("render.trails.glow").advancedOnly());
    public final NumberSetting coreWidth = register(new NumberSetting(
            "Core", 0.35D, 0.05D, 1.0D, 0.05D, ""
    ).configKey("render.trails.core").advancedOnly());
    public final NumberSetting flowSpeed = register(new NumberSetting(
            "Flow Speed", 1.2D, 0.0D, 5.0D, 0.1D, ""
    ).configKey("render.trails.flowspeed").advancedOnly());
    public final NumberSetting chromatic = register(new NumberSetting(
            "Chromatic", 0.4D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.trails.chromatic").advancedOnly());

    public final BooleanSetting bloom = register(new BooleanSetting(
            "Bloom", true
    ).configKey("render.trails.bloom").advancedOnly());
    public final NumberSetting bloomIntensity = register(new NumberSetting(
            "Bloom Intensity", 1.0D, 0.0D, 3.0D, 0.05D, ""
    ).configKey("render.trails.bloomintensity")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting bloomRadius = register(new NumberSetting(
            "Bloom Radius", 3.0D, 1.0D, 4.0D, 1.0D, ""
    ).configKey("render.trails.bloomradius")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting hdrBoost = register(new NumberSetting(
            "HDR Boost", 2.0D, 0.5D, 6.0D, 0.1D, ""
    ).configKey("render.trails.hdrboost")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting anamorphic = register(new NumberSetting(
            "Anamorphic", 0.35D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.trails.anamorphic")
            .visibleWhen(this.bloom::getValue).advancedOnly());

    private final List<Vec3> history = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>(RIBBONS);
    private final GlowStripRenderer renderer = new GlowStripRenderer("furryhook-trails");

    public TrailsFeature() {
        super(
                "Trails",
                "Three glowing ribbons trailing behind the player",
                FeatureCategory.VISUAL,
                BindSetting.UNBOUND
        );
    }

    public static TrailsFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(TrailsFeature.class);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.history.clear();
            return;
        }

        Vec3 head = mc.player.position().add(0.0D, this.height.getValue(), 0.0D);
        if (!this.history.isEmpty()) {
            Vec3 last = this.history.getLast();
            if (last.distanceToSqr(head) > TELEPORT_DISTANCE_SQR) {
                this.history.clear();
            }
        }

        if (this.history.isEmpty() || this.history.getLast().distanceToSqr(head) > MIN_STEP_SQR) {
            this.history.add(head);
        } else if (!this.history.isEmpty()) {
            // Standing still retracts the ribbon from the tail instead of leaving
            // it hanging in the air forever.
            this.history.removeFirst();
        }

        int maximum = this.length.getValue().intValue();
        while (this.history.size() > maximum) {
            this.history.removeFirst();
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        this.history.clear();
    }

    @Override
    protected void onDisable() {
        this.history.clear();
        this.strips.clear();
        this.renderer.release();
    }

    public void renderWorld(float tickDelta) {
        if (mc.player == null || mc.level == null || this.history.size() < 2) {
            return;
        }
        // The history keeps ticking either way, so switching to F5 shows the
        // ribbon already trailing behind rather than growing from nothing.
        if (this.thirdPersonOnly.getValue() && mc.options.getCameraType().isFirstPerson()) {
            return;
        }

        List<Vec3> path = GlowStripRenderer.smooth(buildSpine(tickDelta), SUBDIVISIONS);
        if (path.size() < 2) {
            return;
        }

        int baseColor = ColorMode.resolve(this.colorMode, this.color);
        float time = PostFx.shaderTime();
        float baseWidth = this.width.getValue().floatValue();
        double spreadDistance = this.spread.getValue();
        double waveAmplitude = this.wave.getValue();
        double waveFrequency = this.waveDensity.getValue();
        double wavePhase = time * this.waveSpeed.getValue();

        this.strips.clear();
        for (int ribbon = 0; ribbon < RIBBONS; ribbon++) {
            double offset = (ribbon - 1) * spreadDistance;
            double ribbonPhase = ribbon * TWO_PI / RIBBONS;
            List<Point> points = new ArrayList<>(path.size());
            for (int index = 0; index < path.size(); index++) {
                double progress = (double) index / (path.size() - 1);
                Vec3 side = sideVector(path, index);
                Vec3 position = path.get(index);
                double phase = index * waveFrequency + wavePhase + ribbonPhase;
                double lateral = offset + Math.sin(phase) * waveAmplitude;
                double vertical = Math.cos(phase) * waveAmplitude * 0.6D;
                points.add(new Point(
                        position.add(side.scale(lateral)).add(0.0D, vertical, 0.0D),
                        baseWidth * taper(progress),
                        ColorUtil.multiplyAlpha(baseColor, fade(progress))
                ));
            }
            this.strips.add(new Strip(points));
        }

        this.renderer.render(
                this.strips,
                new GlowStripRenderer.Style(
                        this.glow.getValue().floatValue(),
                        this.coreWidth.getValue().floatValue(),
                        this.chromatic.getValue().floatValue(),
                        this.flowSpeed.getValue().floatValue(),
                        0.0035F,
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

    /**
     * Tick samples plus the interpolated player position on the head, so the
     * ribbon starts at the body rather than one tick behind it.
     */
    private List<Vec3> buildSpine(float tickDelta) {
        List<Vec3> spine = new ArrayList<>(this.history.size() + 1);
        spine.addAll(this.history);
        Vec3 head = Render3DUtil.interpolatedPosition(mc.player, tickDelta)
                .add(0.0D, this.height.getValue(), 0.0D);
        if (spine.getLast().distanceToSqr(head) > 1.0E-6D) {
            spine.add(head);
        }
        return spine;
    }

    /** Horizontal perpendicular to the direction of travel at this node. */
    private static Vec3 sideVector(List<Vec3> path, int index) {
        Vec3 previous = path.get(Math.max(0, index - 1));
        Vec3 next = path.get(Math.min(path.size() - 1, index + 1));
        double dx = next.x - previous.x;
        double dz = next.z - previous.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-5D) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return new Vec3(-dz / length, 0.0D, dx / length);
    }

    private static float taper(double progress) {
        return (float) (TAIL_WIDTH + (1.0D - TAIL_WIDTH) * progress);
    }

    private static float fade(double progress) {
        // Fades into the tail, and pulls the very tip of the head in a little so
        // the ribbon does not end on a hard bright square at the player's back.
        float tail = (float) Math.clamp(progress * 1.6D, 0.0D, 1.0D);
        float head = (float) Math.clamp((1.0D - progress) * 12.0D, 0.0D, 1.0D);
        return tail * head;
    }
}
