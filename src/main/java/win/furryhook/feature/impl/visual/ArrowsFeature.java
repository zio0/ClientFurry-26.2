package win.furryhook.feature.impl.visual;

import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.FriendManager;
import win.furryhook.mixin.accessor.AbstractContainerScreenAccessor;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.Animation;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFontStyle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import win.furryhook.utils.render.Textures;

public final class ArrowsFeature extends Feature implements MinecraftContext {
    private static final int FRIEND_COLOR = 0xFF65E572;
    private static final float RADIUS_TO_GUI_PIXELS = 7.0F;
    private static final float BASE_ARROW_SIZE = 32.0F;
    private static final float GENERIC_SCREEN_EXPANSION = 32.0F;
    private static final long SCREEN_OPEN_MILLIS = 360L;
    private static final long SCREEN_CLOSE_MILLIS = 300L;
    private static final long RADIUS_ADJUST_MILLIS = 180L;

    public final NumberSetting radius = register(new NumberSetting(
            "Radius",
            10.0D,
            8.0D,
            15.0D,
            1.0D,
            ""
    ).configKey("render.arrows.radius"));
    public final NumberSetting scale = register(new NumberSetting(
            "Scale",
            1.0D,
            0.4D,
            1.0D,
            0.1D,
            "x"
    ).configKey("render.arrows.scale"));
    public final BooleanSetting showDistance = register(new BooleanSetting(
            "Show Distance",
            false
    ).configKey("render.arrows.showDistance"));
    public final BooleanSetting onlyFriends = register(new BooleanSetting(
            "Only Friends",
            false
    ).configKey("render.arrows.onlyFriend"));
    public final BooleanSetting filled = register(new BooleanSetting(
            "Filled",
            false
    ).configKey("render.arrows.filled"));
    public final BooleanSetting ignoreNaked = register(new BooleanSetting(
            "Ignore Naked",
            false
    ).configKey("render.arrows.ignoreNaked"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.arrows.colorMode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color",
            0xFFFFFFFF
    ).configKey("render.arrows.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final BooleanSetting armorColorEnabled = register(new BooleanSetting(
            "Armor Color",
            false
    ).configKey("render.arrows.armorColorEnabled"));
    public final ColorSetting armorColor = register(new ColorSetting(
            "Armor Color Value",
            0xFFFF4040
    ).configKey("render.arrows.armorColor")
            .visibleWhen(() -> this.armorColorEnabled.getValue()));

    private final Animation radiusAnimation = new Animation(0L, Animation.Easing.EASE_OUT_CUBIC);
    private final Map<UUID, ArrowState> arrowStates = new HashMap<>();
    private Screen lastScreen;
    private float lastRadiusTarget = Float.NaN;
    private long lastFrameNanos;
    private boolean radiusInitialized;

    public ArrowsFeature() {
        super(
                "Arrows", "Points toward players outside the visible screen",
                FeatureCategory.VISUAL,
                BindSetting.UNBOUND
        );
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) {
            clearAnimations();
            return;
        }

        float delta = frameDelta();
        float tickDelta = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float width = event.getGuiGraphicsExtractor().guiWidth();
        float height = event.getGuiGraphicsExtractor().guiHeight();
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        float arrowSize = BASE_ARROW_SIZE * this.scale.getValue().floatValue();
        Screen screen = mc.gui.screen();
        float maxRadius = maximumRadius(width, height, arrowSize);
        float radiusTarget = targetRadius(screen, width, height, arrowSize, maxRadius);
        float ringRadius = Math.clamp(animateRadius(radiusTarget, screen), 8.0F, maxRadius);
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();
        double yaw = Math.toRadians(camera.yRot());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        int normalColor = ColorMode.resolve(this.colorMode, this.color);
        Set<UUID> touched = new HashSet<>();

        for (Player player : mc.level.players()) {
            if (!eligible(player)) {
                continue;
            }

            Vec3 target = Render3DUtil.interpolatedPosition(player, tickDelta);
            double dx = target.x - cameraPos.x;
            double dz = target.z - cameraPos.z;
            double right = dx * cosYaw - dz * sinYaw;
            double forward = -dx * sinYaw + dz * cosYaw;
            if (right * right + forward * forward < 1.0E-4D) {
                continue;
            }

            boolean visible = isOffscreen(player, width, height, arrowSize, tickDelta);
            UUID id = player.getUUID();
            ArrowState state = this.arrowStates.get(id);
            if (state == null && !visible) {
                continue;
            }
            float targetAngle = (float) Math.atan2(-forward, right);
            if (state == null) {
                state = new ArrowState(targetAngle);
                this.arrowStates.put(id, state);
            }
            state.targetAngle = targetAngle;
            state.targetVisible = visible;
            state.color = resolveColor(player, normalColor);
            state.distance = Math.max(1, Math.round(mc.player.distanceTo(player)));
            touched.add(id);
        }

        this.arrowStates.forEach((id, state) -> {
            if (!touched.contains(id)) {
                state.targetVisible = false;
            }
        });

        Iterator<ArrowState> iterator = this.arrowStates.values().iterator();
        while (iterator.hasNext()) {
            ArrowState state = iterator.next();
            updateState(state, delta);
            if (!state.targetVisible && state.alpha < 0.01F) {
                iterator.remove();
                continue;
            }

            float angle = state.angle;
            float arrowX = centerX + (float) Math.cos(angle) * ringRadius;
            float arrowY = centerY + (float) Math.sin(angle) * ringRadius;
            float animatedSize = arrowSize * state.scale;
            drawArrow(event, arrowX, arrowY, animatedSize, angle, state.color, state.alpha);
            if (this.showDistance.getValue()) {
                drawDistance(event, state.distance, arrowX, arrowY, animatedSize, state.alpha);
            }
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        clearAnimations();
    }

    @Override
    protected void onEnable() {
        clearAnimations();
    }

    @Override
    protected void onDisable() {
        clearAnimations();
    }

    private boolean eligible(Player player) {
        if (player == mc.player || player.isRemoved() || !player.isAlive() || player.isSpectator()) {
            return false;
        }

        boolean friend = FriendManager.INSTANCE.isFriend(player.getGameProfile().name());
        if (this.onlyFriends.getValue() && !friend) {
            return false;
        }
        if (this.ignoreNaked.getValue() && !hasArmor(player)) {
            return false;
        }
        return true;
    }

    private boolean isOffscreen(Player player, float width, float height, float arrowSize, float tickDelta) {
        Vec3 anchor = Render3DUtil.interpolatedPosition(player, tickDelta)
                .add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
        Render3DUtil.ScreenPoint point = Render3DUtil.projectToScreen(mc, anchor);
        float margin = arrowSize * 0.35F;
        return point == null
                || point.x() < margin
                || point.x() > width - margin
                || point.y() < margin
                || point.y() > height - margin;
    }

    private float targetRadius(Screen screen,
                               float width,
                               float height,
                               float arrowSize,
                               float maxRadius) {
        float baseRadius = this.radius.getValue().floatValue() * RADIUS_TO_GUI_PIXELS;
        if (screen == null) {
            return Math.min(baseRadius, maxRadius);
        }

        float expanded = baseRadius + GENERIC_SCREEN_EXPANSION;
        if (screen instanceof AbstractContainerScreen<?> container) {
            AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) container;
            float left = accessor.getLeftPos();
            float top = accessor.getTopPos();
            float right = left + accessor.getImageWidth();
            float bottom = top + accessor.getImageHeight();
            float centerX = width * 0.5F;
            float centerY = height * 0.5F;
            float farthestCorner = Math.max(
                    Math.max(distance(centerX, centerY, left, top), distance(centerX, centerY, right, top)),
                    Math.max(distance(centerX, centerY, left, bottom), distance(centerX, centerY, right, bottom))
            );
            expanded = Math.max(expanded, farthestCorner + arrowSize * 0.6F + 10.0F);
        }
        return Math.min(expanded, maxRadius);
    }

    private float animateRadius(float target, Screen screen) {
        if (!this.radiusInitialized) {
            this.radiusAnimation.animate(target, target, 0L, Animation.Easing.EASE_OUT_CUBIC);
            this.radiusInitialized = true;
            this.lastScreen = screen;
            this.lastRadiusTarget = target;
            return target;
        }

        boolean screenChanged = screen != this.lastScreen;
        boolean targetChanged = Math.abs(target - this.lastRadiusTarget) > 0.25F;
        if (screenChanged || targetChanged) {
            boolean opening = this.lastScreen == null && screen != null;
            boolean closing = this.lastScreen != null && screen == null;
            long duration = opening
                    ? SCREEN_OPEN_MILLIS
                    : closing ? SCREEN_CLOSE_MILLIS : RADIUS_ADJUST_MILLIS;
            Animation.Easing easing = opening
                    ? Animation.Easing.EASE_OUT_BACK
                    : Animation.Easing.EASE_OUT_CUBIC;
            this.radiusAnimation.animate(this.radiusAnimation.getValue(), target, duration, easing);
            this.lastScreen = screen;
            this.lastRadiusTarget = target;
        }
        return this.radiusAnimation.getValue();
    }

    private float frameDelta() {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L
                ? 1.0F / 60.0F
                : (now - this.lastFrameNanos) / 1_000_000_000.0F;
        this.lastFrameNanos = now;
        return Math.clamp(delta, 0.001F, 0.05F);
    }

    private void updateState(ArrowState state, float delta) {
        float angleBlend = smoothing(18.0F, delta);
        state.angle += shortestAngle(state.angle, state.targetAngle) * angleBlend;

        float alphaTarget = state.targetVisible ? 1.0F : 0.0F;
        float alphaSpeed = state.targetVisible ? 13.0F : 10.0F;
        state.alpha += (alphaTarget - state.alpha) * smoothing(alphaSpeed, delta);

        float scaleTarget = state.targetVisible ? 1.0F : 0.78F;
        state.scale += (scaleTarget - state.scale) * smoothing(15.0F, delta);
    }

    private int resolveColor(Player player, int normalColor) {
        if (FriendManager.INSTANCE.isFriend(player.getGameProfile().name())) {
            return FRIEND_COLOR;
        }
        if (this.armorColorEnabled.getValue() && hasArmor(player)) {
            return this.armorColor.getValue();
        }
        return normalColor;
    }

    private void drawArrow(Render2DEvent event,
                           float x,
                           float y,
                           float size,
                           float angle,
                           int color,
                           float alpha) {
        var pose = event.getGuiGraphicsExtractor().pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate(angle);
        Render2DUtil.texture(
                        -size * 0.5F,
                        -size * 0.5F,
                        size,
                        size,
                        this.filled.getValue() ? Textures.Hud.ARROW_FILLED : Textures.Hud.ARROW_OUTLINE
                )
                .color(ColorUtil.multiplyAlpha(color, 0.95F * alpha))
                .draw();
        pose.popMatrix();
    }

    private void drawDistance(Render2DEvent event,
                              int distance,
                              float x,
                              float y,
                              float arrowSize,
                              float alpha) {
        float textSize = 8.0F + 2.0F * this.scale.getValue().floatValue();
        Render2DUtil.text(x, y + arrowSize * 0.52F, textSize, distance + "m")
                .style(UiFontStyle.SEMIBOLD)
                .align(TextAlign.CENTER)
                .color(ColorUtil.multiplyAlpha(0xFFFFFFFF, alpha))
                .draw();
    }

    private void clearAnimations() {
        this.arrowStates.clear();
        this.lastScreen = null;
        this.lastRadiusTarget = Float.NaN;
        this.lastFrameNanos = 0L;
        this.radiusInitialized = false;
    }

    private static float maximumRadius(float width, float height, float arrowSize) {
        return Math.max(8.0F, Math.min(width, height) * 0.5F - arrowSize * 0.55F - 8.0F);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static float shortestAngle(float from, float to) {
        return (float) Math.atan2(Math.sin(to - from), Math.cos(to - from));
    }

    private static float smoothing(float speed, float delta) {
        return 1.0F - (float) Math.exp(-speed * delta);
    }

    private static boolean hasArmor(Player player) {
        return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private static final class ArrowState {
        float angle;
        float targetAngle;
        float alpha;
        float scale = 0.72F;
        int color;
        int distance;
        boolean targetVisible;

        ArrowState(float angle) {
            this.angle = angle;
            this.targetAngle = angle;
        }
    }
}
