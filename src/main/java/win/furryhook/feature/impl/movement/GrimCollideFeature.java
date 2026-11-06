package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class GrimCollideFeature extends Feature {
    public final NumberSetting boost = register(new NumberSetting("Boost", 0.08, 0.01, 0.08, 0.01, " b/t"));
    public final NumberSetting collisionRange = register(new NumberSetting("Collision Range", 0.5, 0.1, 2.0, 0.1, ""));

    public GrimCollideFeature() {
        super("GrimCollide", "Uses movement allowance near living entities", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null || event.getClient().level == null || player.input.getMoveVector().equals(Vec2.ZERO)) {
            return;
        }

        AABB box = player.getBoundingBox().inflate(collisionRange.getValue());
        int collisions = event.getClient().level.getEntities(player, box, entity -> isCollidableLivingEntity(player, entity))
                .stream()
                .filter(entity -> box.intersects(entity.getBoundingBox()))
                .toList()
                .size();
        if (collisions == 0) {
            return;
        }

        double yaw = Math.toRadians(player.getYRot());
        double speed = boost.getValue() * collisions;
        player.push(-Math.sin(yaw) * speed, 0.0, Math.cos(yaw) * speed);
    }

    private boolean isCollidableLivingEntity(LocalPlayer player, Entity entity) {
        return entity != player && entity instanceof LivingEntity && !(entity instanceof ArmorStand);
    }
}
