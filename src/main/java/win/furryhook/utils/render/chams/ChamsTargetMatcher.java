package win.furryhook.utils.render.chams;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import win.furryhook.feature.impl.visual.ChamsFeature;

import java.util.ArrayList;
import java.util.List;

public final class ChamsTargetMatcher {
    private ChamsTargetMatcher() {
    }

    public static List<Entity> collectTargets(Minecraft minecraft, ChamsFeature feature) {
        if (minecraft.level == null || feature == null || !feature.hasAnyVisual()) {
            return List.of();
        }
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (feature.shouldRender(entity)) {
                targets.add(entity);
            }
        }
        return targets;
    }

    public static Entity matchingTarget(EntityRenderState state, List<Entity> targets) {
        if (state instanceof AvatarRenderState avatarState) {
            for (Entity entity : targets) {
                if (entity.getId() == avatarState.id) {
                    return entity;
                }
            }
        }

        Entity best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Entity entity : targets) {
            if (entity.getType() != state.entityType) {
                continue;
            }
            double dx = entity.getX() - state.x;
            double dy = entity.getY() - state.y;
            double dz = entity.getZ() - state.z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < bestDistanceSq) {
                best = entity;
                bestDistanceSq = distanceSq;
            }
        }
        return bestDistanceSq <= 9.0D ? best : null;
    }
}
