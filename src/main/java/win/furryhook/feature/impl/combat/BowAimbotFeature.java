package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.math.TickSimulator;

public final class BowAimbotFeature extends Feature {
    private static final int MAX_FLIGHT_TICKS = 120;
    private static final double MAX_HIT_ERROR_SQR = 3.5D * 3.5D;

    private boolean isAiming = false;

    public final NumberSetting range = register(new NumberSetting("Range", 50.0, 5.0, 100.0, 5.0, " blocks"));

    public BowAimbotFeature() {
        super("BowAimbot", "Predicts target movement and arrow ballistics", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        RotationContext.clear();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !player.isUsingItem() || !(player.getUseItem().getItem() instanceof BowItem)) {
            if (isAiming) {
                RotationContext.clear();
                isAiming = false;
            }
            return;
        }

        LivingEntity target = findClosestTarget(player, level);
        if (target == null) {
            if (isAiming) {
                RotationContext.clear();
                isAiming = false;
            }
            return;
        }

        float force = player.getTicksUsingItem(0.0F) / 20.0F;
        force = Math.min(1.0F, (force * force + force * 2.0F) / 3.0F);
        if (force < 0.1F) {
            if (isAiming) {
                RotationContext.clear();
                isAiming = false;
            }
            return;
        }

        Vec3 predictedPos = TickSimulator.getPredictedState(target, 15, level).pos;
        double targetY = predictedPos.y + target.getBbHeight() * 0.5D;
        double diffX = predictedPos.x - player.getX();
        double diffZ = predictedPos.z - player.getZ();
        double distanceXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0F;
        double basePitch = -Math.toDegrees(Math.atan2(targetY - player.getEyeY(), distanceXZ));
        double velocity = force * 3.0D;

        float bestPitch = player.getXRot();
        double bestScore = Double.MAX_VALUE;
        double bestApexX = player.getX();
        double bestApexY = player.getEyeY() - 0.1D;
        double bestApexZ = player.getZ();
        double bestEndX = bestApexX;
        double bestEndY = bestApexY;
        double bestEndZ = bestApexZ;

        for (float pitch = (float) basePitch + 5.0F; pitch > -85.0F; pitch -= 0.5F) {
            double pitchRad = Math.toRadians(pitch);
            double yawRad = Math.toRadians(yaw);
            double motionX = -Math.sin(yawRad) * Math.cos(pitchRad) * velocity;
            double motionY = -Math.sin(pitchRad) * velocity;
            double motionZ = Math.cos(yawRad) * Math.cos(pitchRad) * velocity;
            double arrowX = player.getX();
            double arrowY = player.getEyeY() - 0.1D;
            double arrowZ = player.getZ();
            double apexX = arrowX;
            double apexY = arrowY;
            double apexZ = arrowZ;
            double closestX = arrowX;
            double closestY = arrowY;
            double closestZ = arrowZ;
            double score = Double.MAX_VALUE;

            for (int step = 0; step < MAX_FLIGHT_TICKS; step++) {
                arrowX += motionX;
                arrowY += motionY;
                arrowZ += motionZ;
                if (arrowY > apexY) {
                    apexX = arrowX;
                    apexY = arrowY;
                    apexZ = arrowZ;
                }

                double dx = arrowX - predictedPos.x;
                double dy = arrowY - targetY;
                double dz = arrowZ - predictedPos.z;
                double distanceSqr = dx * dx + dy * dy + dz * dz;
                if (distanceSqr < score) {
                    score = distanceSqr;
                    closestX = arrowX;
                    closestY = arrowY;
                    closestZ = arrowZ;
                }

                motionX *= 0.99D;
                motionY = motionY * 0.99D - 0.05D;
                motionZ *= 0.99D;
                if (motionY < 0.0D && arrowY < predictedPos.y - 1.0D) {
                    break;
                }
            }

            if (score < bestScore) {
                bestScore = score;
                bestPitch = pitch;
                bestApexX = apexX;
                bestApexY = apexY;
                bestApexZ = apexZ;
                bestEndX = closestX;
                bestEndY = closestY;
                bestEndZ = closestZ;
            }
        }

        if (bestScore < MAX_HIT_ERROR_SQR && isPathClear(level, player,
                bestApexX, bestApexY, bestApexZ, bestEndX, bestEndY, bestEndZ)) {
            RotationContext.setRotation(yaw, bestPitch);
            isAiming = true;
        } else if (isAiming) {
            RotationContext.clear();
            isAiming = false;
        }
    }

    private boolean isPathClear(ClientLevel level, LocalPlayer player,
                                double apexX, double apexY, double apexZ,
                                double endX, double endY, double endZ) {
        Vec3 start = new Vec3(player.getX(), player.getEyeY() - 0.1D, player.getZ());
        Vec3 apex = new Vec3(apexX, apexY, apexZ);
        Vec3 end = new Vec3(endX, endY, endZ);
        if (start.distanceToSqr(apex) > 0.01D && isBlocked(level, player, start, apex)) {
            return false;
        }
        return apex.distanceToSqr(end) <= 0.01D || !isBlocked(level, player, apex, end);
    }

    private boolean isBlocked(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
                == HitResult.Type.BLOCK;
    }

    private LivingEntity findClosestTarget(LocalPlayer player, ClientLevel level) {
        double maxRange = range.getValue();
        double closestDistance = maxRange * maxRange;
        LivingEntity closest = null;
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(maxRange), this::isTarget)) {
            double distance = player.distanceToSqr(entity);
            if (distance < closestDistance && entity instanceof LivingEntity living) {
                closestDistance = distance;
                closest = living;
            }
        }
        return closest;
    }

    private boolean isTarget(Entity entity) {
        return entity instanceof LivingEntity && entity.isAlive() && entity.isAttackable() && !entity.isSpectator();
    }
}
