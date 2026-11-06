package win.furryhook.utils.combat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.FriendManager;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class TargetUtil {

    private TargetUtil() {
    }

    public static LivingEntity getBestTarget(LocalPlayer player, ClientLevel level, double range, TargetFilter filter) {
        return getBestTarget(player, level, range, filter, entity -> true);
    }

    public static LivingEntity getBestTarget(
            LocalPlayer player,
            ClientLevel level,
            double range,
            TargetFilter filter,
            Predicate<LivingEntity> extraFilter
    ) {
        List<LivingEntity> targets = getTargets(player, level, range, filter);
        targets.removeIf(extraFilter.negate());
        if (targets.isEmpty()) {
            return null;
        }

        targets.sort(Comparator.comparingDouble(entity -> calculateTargetScore(player, entity, range, filter)));
        return targets.get(0);
    }

    public static List<LivingEntity> getTargets(LocalPlayer player, ClientLevel level, double range, TargetFilter filter) {
        double rangeSqr = range * range;

        return StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
                .filter(entity -> entity instanceof LivingEntity)
                .map(entity -> (LivingEntity) entity)
                .filter(entity -> isValidTarget(player, entity, rangeSqr, filter))
                .collect(Collectors.toList());
    }

    public static boolean isValidTarget(LocalPlayer player, LivingEntity entity, double rangeSqr, TargetFilter filter) {
        if (entity == player) return false;
        if (!entity.isAlive() || !entity.isAttackable()) return false;
        if (player.distanceToSqr(entity) > rangeSqr) return false;

        if (entity.isInvisible() && !filter.isTargetsInvisibles()) {
            return false;
        }

        if (entity instanceof Player targetPlayer) {
            if (targetPlayer.isSpectator() || targetPlayer.isCreative()) return false;
            if (FriendManager.INSTANCE.isFriend(targetPlayer.getGameProfile().name()) && !filter.isTargetsFriends()) return false;
            if (isNaked(targetPlayer) && !filter.isTargetsNakedPlayers()) return false;
            if (!filter.isTargetsPlayers()) return false;

        } else if (entity instanceof Monster) {
            if (!filter.isTargetsMonsters()) return false;
        } else if (entity instanceof Animal || entity instanceof AmbientCreature) {
            if (!filter.isTargetsAnimals()) return false;
        } else if (entity instanceof Villager) {
            if (!filter.isTargetsVillagers()) return false;
        } else {
            return false;
        }

        return true;
    }

    private static double calculateTargetScore(LocalPlayer player, LivingEntity entity, double maxRange, TargetFilter filter) {
        double distance = player.distanceTo(entity);
        double normalizedDist = distance / maxRange;

        double normalizedHealth = entity.getHealth() / entity.getMaxHealth();
        double normalizedArmor = entity.getArmorValue() / 20.0;

        double fov = RotationContext.getFovToEntity(player, entity);
        double normalizedFov = fov / 180.0;

        return (normalizedDist * filter.getDistanceWeight())
                + (normalizedHealth * filter.getHealthWeight())
                + (normalizedArmor * filter.getArmorWeight())
                + (normalizedFov * filter.getFovWeight());
    }

    private static boolean isNaked(Player player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                ItemStack stack = player.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
}
