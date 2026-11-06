package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.tags.ItemTags;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.combat.*;

import java.security.SecureRandom;
import java.util.List;

public final class TriggerBotFeature extends Feature implements MinecraftContext {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public final MultiSelectSetting targets = register(new MultiSelectSetting("Targets",
            List.of("Players", "Naked"),
            "Players", "Friends", "Naked", "Animals", "Mobs"));

    public final MultiSelectSetting checks = register(new MultiSelectSetting(
            "Checks",
            List.of("Disable on death"),
            "Disable on death", "Don't eat & hit", "Weapon only", "TPSSync"
    ));

    public final NumberSetting attackRange = register(new NumberSetting(
            "Attack Range", 3.0D, 2.5D, 6.0D, 0.1D, " blocks"
    ));

    public final NumberSetting minAttackDelay = register(new NumberSetting(
            "Min Delay", 0.0D, 0.0D, 500.0D, 1.0D, " ms"
    ));

    public final NumberSetting maxAttackDelay = register(new NumberSetting(
            "Max Delay", 0.0D, 0.0D, 500.0D, 1.0D, " ms"
    ));

    public final BooleanSetting onlycrit = register(new BooleanSetting(
            "Only Criticals", false
    ));

    public final BooleanSetting onlySpaceCritical = register(new BooleanSetting(
            "Smart Criticals", false
    ));

    public final BooleanSetting throughWalls = register(new BooleanSetting(
            "Through Walls", true
    ));

    private LivingEntity currentTarget;
    private long attackDelayTime = 0L;
    private boolean wasEating = false;
    private long lastEatingEndTime = -1L;
    private long currentEatingBoostDuration = 1500L;

    public TriggerBotFeature() {
        super("TriggerBot", "Attacks the entity you are aiming at", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    public static TriggerBotFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(TriggerBotFeature.class);
    }

    public LivingEntity getCurrentTarget() {
        return isEnabled() ? this.currentTarget : null;
    }

    @Override
    protected void onDisable() {
        this.currentTarget = null;
        this.attackDelayTime = 0L;
        SprintManager.reset();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        this.currentTarget = null;
        this.attackDelayTime = 0L;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        boolean isEatingNow = player.isUsingItem();
        if (isEatingNow && !wasEating) {
            wasEating = true;
        } else if (!isEatingNow && wasEating) {
            wasEating = false;
            lastEatingEndTime = System.currentTimeMillis();
            currentEatingBoostDuration = 1000L + (long) (Math.random() * 1500L);
        }

        this.currentTarget = crosshairTarget(mc, player);
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!event.isPre()) {
            return;
        }
        LocalPlayer player = event.getPlayer();
        LivingEntity target = this.currentTarget;
        if (player == null || target == null || !target.isAlive()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (attackDelayTime == 0L) {
            long minD = minAttackDelay.getValue().longValue();
            long maxD = maxAttackDelay.getValue().longValue();
            long delay = minD;
            if (maxD > minD) {
                delay = minD + (long) (SECURE_RANDOM.nextDouble() * (maxD - minD));
            }
            attackDelayTime = now + delay;
        }

        if (now >= attackDelayTime) {
            boolean readyToStrike;
            if (onlycrit.getValue() || onlySpaceCritical.getValue()) {
                readyToStrike = canAttack(player);
            } else {
                readyToStrike = checkCooldownAndConditions(player);
            }

            if (readyToStrike) {
                SprintManager.markAttackImminent();
                if (mc.gameMode != null) {
                    win.furryhook.feature.impl.movement.AirStuckFeature airStuck =
                            FeatureManager.INSTANCE.getEnabled(win.furryhook.feature.impl.movement.AirStuckFeature.class);
                    if (airStuck != null) {
                        airStuck.pauseForAttack();
                    }
                    mc.gameMode.attack(player, target);
                    player.swing(InteractionHand.MAIN_HAND);
                }
                attackDelayTime = 0L;
            }
        }
    }

    private boolean checkCooldownAndConditions(LocalPlayer player) {
        if (checkReturn(player)) return false;
        boolean attackReady = player.getAttackStrengthScale(checks.isSelected("TPSSync") ? 1.0F : 0.0F) >= 0.85F;
        return attackReady;
    }

    private boolean canAttack(LocalPlayer player) {
        if (!checkCooldownAndConditions(player)) return false;

        boolean canCritEnvironment = !player.onClimbable() && !player.isInWater() && !player.isInLava() && !player.isPassenger();

        if (canCritEnvironment) {
            boolean isJumpPressed = (player.input != null && player.input.keyPresses.jump()) || mc.options.keyJump.isDown();
            boolean isRecrit = !player.onGround() && player.hurtTime > 0;

            boolean wantCrit = (onlycrit.getValue() && !onlySpaceCritical.getValue()) ||
                               (onlySpaceCritical.getValue() && (isJumpPressed || isRecrit));

            if (wantCrit) {
                if (player.onGround()) return false;
                boolean isFalling = player.fallDistance > 0.0F && player.getDeltaMovement().y < 0.0D;
                return isFalling;
            }
        }
        return true;
    }

    private boolean checkReturn(LocalPlayer player) {
        if (player.isDeadOrDying() && checks.isSelected("Disable on death")) {
            setEnabled(false);
            return true;
        }
        if (checks.isSelected("Weapon only") && !(player.getMainHandItem().is(ItemTags.AXES) || player.getMainHandItem().is(ItemTags.SWORDS))) {
            return true;
        }
        if (checks.isSelected("Don't eat & hit")) {
            boolean isEatingNow = player.isUsingItem();
            if (isEatingNow) return true;
        }
        return false;
    }

    private LivingEntity crosshairTarget(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        double reach = attackRange.getValue().doubleValue();

        // 1. Check vanilla crosshair pick entity
        if (mc.crosshairPickEntity instanceof LivingEntity living) {
            if (isValidTarget(living, player, reach)) {
                return living;
            }
        }

        // 2. Perform crosshair raycast scan using player view angles
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && isValidTarget(living, player, reach)) {
                if (AuraRaycast.rotationIntersectsTarget(player, living, player.getYRot(), player.getXRot(), reach, 0.0D, 0.2D, throughWalls.getValue())) {
                    return living;
                }
            }
        }

        return null;
    }

    private boolean isValidTarget(LivingEntity entity, LocalPlayer player, double reach) {
        if (entity == player) return false;
        if (entity.tickCount < 3) return false;
        if (player.distanceTo(entity) > reach) return false;

        if (entity instanceof Player playerEntity) {
            if (!targets.isSelected("Friends") && FriendManager.INSTANCE.isFriend(playerEntity.getGameProfile().name())) {
                return false;
            }
            if (playerEntity.getGameProfile().name().equalsIgnoreCase(player.getGameProfile().name())) {
                return false;
            }
            if (playerEntity.getArmorValue() == 0 && !targets.isSelected("Naked")) return false;
            if (!targets.isSelected("Players")) return false;
        }

        if ((entity instanceof Monster || entity instanceof Phantom || entity instanceof Bat || entity instanceof Shulker || entity instanceof Villager) && !targets.isSelected("Mobs")) {
            return false;
        }

        if ((entity instanceof Animal || entity instanceof AmbientCreature) && !targets.isSelected("Animals")) {
            return false;
        }

        return !entity.isInvulnerable() && entity.isAlive() && !(entity instanceof ArmorStand);
    }
}
