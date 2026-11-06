package win.furryhook.feature.impl.combat;

import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.render.Render3DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class HitBoxesFeature extends Feature implements MinecraftContext {
    private static final double MAX_RENDER_DISTANCE_SQR = 64.0D * 64.0D;
    private static final int HITBOX_COLOR = 0xFF00FF7F;
    private static final int EYE_COLOR = 0xFFFF5555;
    private static final float LINE_WIDTH = 1.5F;

    public final NumberSetting size = register(new NumberSetting("Size", 3.0D, 1.0D, 10.0D, 0.5D, ""));
    public final BooleanSetting withAura = register(new BooleanSetting("With Aura", false));
    public final BooleanSetting showSize = register(new BooleanSetting("Show Size", false));
    public final BooleanSetting yModification = register(new BooleanSetting("Y Size", false));

    public HitBoxesFeature() {
        super("HitBoxes", "Expands the collision size of surrounding entities for easier hits", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    public static HitBoxesFeature getInstance() {
        return FeatureManager.INSTANCE.getFeature(HitBoxesFeature.class);
    }

    public static HitBoxesFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(HitBoxesFeature.class);
    }

    public double getHorizontalExpansion() {
        return size.getValue() / 10.0D;
    }

    public double getVerticalExpansion() {
        return yModification.getValue() ? getHorizontalExpansion() : 0.0D;
    }

    public double getAuraExpansion() {
        return isEnabled() && withAura.getValue() ? getHorizontalExpansion() : 0.0D;
    }

    public AABB expandBoundingBox(AABB boundingBox) {
        double horizontal = getHorizontalExpansion();
        return boundingBox.inflate(horizontal, getVerticalExpansion(), horizontal);
    }

    public boolean appliesTo(Entity entity) {
        return isEnabled() && entity instanceof LivingEntity livingEntity && livingEntity.isAlive();
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!isEnabled() || !showSize.getValue() || mc.player == null || mc.level == null || event.getClient().levelRenderer == null) {
            return;
        }

        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        SimpleGizmoCollector collector = new SimpleGizmoCollector();
        try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(collector)) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity livingEntity) || !livingEntity.isAlive() || entity == mc.player) {
                    continue;
                }
                if (mc.player.distanceToSqr(entity) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }

                AABB box = interpolatedBox(livingEntity, partialTick);
                Gizmos.addGizmo(new BoundingBoxGizmo(box, HITBOX_COLOR, LINE_WIDTH)).setAlwaysOnTop();

                double eyeY = Mth.lerp(partialTick, livingEntity.yOld, livingEntity.getY()) + livingEntity.getEyeHeight();
                AABB eyeBox = new AABB(box.minX, eyeY - 0.01D, box.minZ, box.maxX, eyeY + 0.01D, box.maxZ);
                Gizmos.addGizmo(new BoundingBoxGizmo(eyeBox, EYE_COLOR, LINE_WIDTH)).setAlwaysOnTop();
            }
        }

        event.getClient().levelRenderer.addMainThreadGizmos(collector.drainGizmos());
    }

    private AABB interpolatedBox(LivingEntity entity, float partialTick) {
        AABB box = expandBoundingBox(entity.getBoundingBox());
        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        return box.move(renderX - entity.getX(), renderY - entity.getY(), renderZ - entity.getZ());
    }

    private record BoundingBoxGizmo(AABB box, int color, float lineWidth) implements Gizmo {
        @Override
        public void emit(GizmoPrimitives primitives, float alpha) {
            Vec3 minMinMin = new Vec3(box.minX, box.minY, box.minZ);
            Vec3 minMinMax = new Vec3(box.minX, box.minY, box.maxZ);
            Vec3 minMaxMin = new Vec3(box.minX, box.maxY, box.minZ);
            Vec3 minMaxMax = new Vec3(box.minX, box.maxY, box.maxZ);
            Vec3 maxMinMin = new Vec3(box.maxX, box.minY, box.minZ);
            Vec3 maxMinMax = new Vec3(box.maxX, box.minY, box.maxZ);
            Vec3 maxMaxMin = new Vec3(box.maxX, box.maxY, box.minZ);
            Vec3 maxMaxMax = new Vec3(box.maxX, box.maxY, box.maxZ);

            primitives.addLine(minMinMin, minMinMax, color, lineWidth);
            primitives.addLine(minMinMin, minMaxMin, color, lineWidth);
            primitives.addLine(minMinMin, maxMinMin, color, lineWidth);
            primitives.addLine(minMinMax, minMaxMax, color, lineWidth);
            primitives.addLine(minMinMax, maxMinMax, color, lineWidth);
            primitives.addLine(minMaxMin, minMaxMax, color, lineWidth);
            primitives.addLine(minMaxMin, maxMaxMin, color, lineWidth);
            primitives.addLine(maxMinMin, maxMinMax, color, lineWidth);
            primitives.addLine(maxMinMin, maxMaxMin, color, lineWidth);
            primitives.addLine(maxMinMax, maxMaxMax, color, lineWidth);
            primitives.addLine(minMaxMax, maxMaxMax, color, lineWidth);
            primitives.addLine(maxMaxMin, maxMaxMax, color, lineWidth);
        }
    }
}
