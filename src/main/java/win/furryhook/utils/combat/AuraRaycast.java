package win.furryhook.utils.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class AuraRaycast {
    private static final float PARTIAL_TICK = 1.0F;
    private static final double EPSILON = 1.0E-7D;

    private AuraRaycast() {
    }

    public static boolean rotationIntersectsTarget(
            LocalPlayer player,
            LivingEntity target,
            float yaw,
            float pitch,
            double maxReach,
            double minReach,
            double margin,
            boolean throughWalls
    ) {
        Vec3 eye = player.getEyePosition(PARTIAL_TICK);
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        double castDistance = maxReach + margin;
        Vec3 end = eye.add(direction.scale(castDistance));

        AABB box = target.getBoundingBox().inflate(target.getPickRadius());

        if (box.distanceToSqr(eye) <= EPSILON) {
            return minReach - margin <= EPSILON;
        }
        Optional<Vec3> clip = box.clip(eye, end);
        if (clip.isEmpty()) {
            return false;
        }
        Vec3 hit = clip.get();
        double distance = hit.distanceTo(eye);
        if (distance > castDistance + EPSILON || distance < minReach - margin - EPSILON) {
            return false;
        }
        return throughWalls || isBlockPathClear(player, eye, hit);
    }

    public static boolean canSeeTarget(LocalPlayer player, LivingEntity target, double range) {
        return findAimPoint(player, target, target.position(), range, false, Vec3.ZERO) != null;
    }

    public static Vec3 findAimPoint(
            LocalPlayer player,
            LivingEntity target,
            Vec3 predictedPosition,
            double range,
            boolean throughWalls,
            Vec3 jitter
    ) {
        Vec3 eye = player.getEyePosition(PARTIAL_TICK);
        Vec3 movement = predictedPosition.subtract(target.position());
        AABB box = target.getBoundingBox().move(movement).inflate(target.getPickRadius());
        double rangeSqr = range * range;

        if (box.distanceToSqr(eye) <= EPSILON) {
            Vec3 center = box.getCenter();
            if (center.distanceToSqr(eye) <= EPSILON) {
                center = eye.add(Vec3.directionFromRotation(player.getXRot(), player.getYRot()).scale(0.01D));
            }
            return center;
        }

        double targetEyeY = predictedPosition.y + target.getEyeHeight();
        double torsoY = Mth.clamp(targetEyeY - 0.15D, box.minY + 0.1D, box.maxY - 0.1D);
        Vec3 centerAim = new Vec3((box.minX + box.maxX) * 0.5D, torsoY, (box.minZ + box.maxZ) * 0.5D);

        Vec3 primaryCandidate = new Vec3(
                Mth.clamp(centerAim.x + jitter.x * 0.25D, box.minX, box.maxX),
                Mth.clamp(centerAim.y + jitter.y * 0.25D, box.minY, box.maxY),
                Mth.clamp(centerAim.z + jitter.z * 0.25D, box.minZ, box.maxZ)
        );

        if (primaryCandidate.distanceToSqr(eye) <= rangeSqr + EPSILON) {
            if (throughWalls || isBlockPathClear(player, eye, primaryCandidate)) {
                return primaryCandidate;
            }
        }

        List<Vec3> candidates = aimCandidates(box, eye, targetEyeY);
        candidates.sort(Comparator.comparingDouble(primaryCandidate::distanceToSqr));
        for (Vec3 candidate : candidates) {
            if (candidate.distanceToSqr(eye) > rangeSqr + EPSILON) {
                continue;
            }
            if (throughWalls || isBlockPathClear(player, eye, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static double predictedHitboxDistanceSqr(LocalPlayer player, LivingEntity target, Vec3 predictedPosition) {
        Vec3 movement = predictedPosition.subtract(target.position());
        AABB box = target.getBoundingBox().move(movement).inflate(target.getPickRadius());
        return box.distanceToSqr(player.getEyePosition(PARTIAL_TICK));
    }

    private static boolean isBlockPathClear(LocalPlayer player, Vec3 from, Vec3 to) {
        BlockHitResult blockHit = player.level().clip(new ClipContext(
                from,
                to,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        return blockHit.getType() == HitResult.Type.MISS
                || blockHit.getLocation().distanceToSqr(from) + EPSILON >= to.distanceToSqr(from);
    }

    private static List<Vec3> aimCandidates(AABB box, Vec3 eye, double predictedEyeY) {
        List<Vec3> points = new ArrayList<>(7);
        points.add(box.getCenter());
        points.add(new Vec3((box.minX + box.maxX) * 0.5D, Mth.clamp(predictedEyeY, box.minY, box.maxY), (box.minZ + box.maxZ) * 0.5D));
        points.add(new Vec3(eye.x, box.minY + (box.maxY - box.minY) * 0.75D, eye.z));
        points.add(new Vec3(box.minX, (box.minY + box.maxY) * 0.5D, box.minZ));
        points.add(new Vec3(box.maxX, (box.minY + box.maxY) * 0.5D, box.maxZ));
        points.add(new Vec3(box.minX, (box.minY + box.maxY) * 0.5D, box.maxZ));
        points.add(new Vec3(box.maxX, (box.minY + box.maxY) * 0.5D, box.minZ));
        return points;
    }
}
