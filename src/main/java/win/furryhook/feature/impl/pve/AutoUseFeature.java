package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.misc.DonItems;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.utils.inventory.DropAllInventoryController;
import win.furryhook.utils.inventory.InventorySwap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AutoUseFeature extends PveFeature {
    public static final String AUTO_EAT = "Auto Eat";
    public static final String AUTO_INVISIBILITY = "Auto Invisibility";

    private static final long INVISIBILITY_RETRY_NANOS = 4_000_000_000L;

    public final MultiSelectSetting features = register(new MultiSelectSetting(
            "Features",
            Set.of(AUTO_EAT),
            AUTO_EAT,
            AUTO_INVISIBILITY
    ));
    public final BooleanSetting ignoreGoldenApples = register(new BooleanSetting(
            "Ignore Golden Apples",
            false
    ).visibleWhen(() -> this.features.isSelected(AUTO_EAT)));
    public final BooleanSetting ignoreEnchantedGoldenApples = register(new BooleanSetting(
            "Ignore Enchanted Golden Apples",
            false
    ).visibleWhen(() -> this.features.isSelected(AUTO_EAT)));

    private enum Action {
        EATING,
        INVISIBILITY
    }

    private final ConsumableUseController useController = new ConsumableUseController();
    private Action activeAction;
    private boolean rotationApplied;
    private long invisibilityRetryAt;

    public AutoUseFeature() {
        super(
                "AutoUse",
                "Automatically eats food and maintains invisibility",
                FeatureCategory.PLAYER,
                BindSetting.UNBOUND,
                AutomationPriority.BACKGROUND
        );
    }

    public boolean isEating() {
        return this.activeAction == Action.EATING && this.useController.isActive();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;

        if (this.useController.isActive()) {
            if (!isWorldUsable(client, player) || combatActive()) {
                cancelActive(client, player);
                return;
            }
            keepThrowableRotation(player);
            if (this.useController.tick(client, player)) {
                return;
            }
            if (this.activeAction == Action.INVISIBILITY) {
                this.invisibilityRetryAt =
                        System.nanoTime() + INVISIBILITY_RETRY_NANOS;
            }
            finishTransaction();
            return;
        }

        if (!isWorldUsable(client, player)
                || combatActive()
                || client.gui.screen() != null
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || player.isUsingItem()
                || client.options.keyUse.isDown()
                || DropAllInventoryController.blocksInventoryOperations()
                || InventorySwap.isBusy()) {
            return;
        }

        if (this.features.isSelected(AUTO_EAT)
                && player.getFoodData().needsFood()
                && tryAutoEat(client, player)) {
            return;
        }

        if (this.features.isSelected(AUTO_INVISIBILITY)) {
            tryAutoInvisibility(client, player);
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        cancelActive(Minecraft.getInstance(), Minecraft.getInstance().player);
        this.invisibilityRetryAt = 0L;
    }

    @Override
    protected void onPveDisable() {
        cancelActive(Minecraft.getInstance(), Minecraft.getInstance().player);
        this.invisibilityRetryAt = 0L;
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        cancelActive(Minecraft.getInstance(), Minecraft.getInstance().player);
    }

    private boolean tryAutoEat(Minecraft client, LocalPlayer player) {
        List<ConsumableSelector.Candidate<ItemStack>> candidates = ConsumableInventory
                .collect(player, AutoUseFeature::foodProfile)
                .stream()
                .filter(candidate -> !player.getCooldowns().isOnCooldown(candidate.value()))
                .toList();
        Optional<ConsumableSelector.Candidate<ItemStack>> selected =
                ConsumableSelector.selectFood(
                        candidates,
                        this.ignoreGoldenApples.getValue(),
                        this.ignoreEnchantedGoldenApples.getValue()
                );
        return selected.isPresent()
                && startUse(client, player, selected.get(), Action.EATING);
    }

    private boolean tryAutoInvisibility(Minecraft client, LocalPlayer player) {
        long now = System.nanoTime();
        if (now < this.invisibilityRetryAt
                || !player.onGround()
                || player.tickCount <= 100
                || player.hasEffect(MobEffects.INVISIBILITY)
                || player.hasEffect(MobEffects.GLOWING)) {
            return false;
        }

        Optional<ConsumableSelector.Candidate<ItemStack>> selected =
                ConsumableInventory.collect(player, AutoUseFeature::invisibilityProfile)
                        .stream()
                        .filter(candidate ->
                                !player.getCooldowns().isOnCooldown(candidate.value()))
                        .min(Comparator
                                .comparingInt((ConsumableSelector.Candidate<ItemStack> candidate) ->
                                        locationRank(candidate.location()))
                                .thenComparingInt(
                                        ConsumableSelector.Candidate::containerSlot
                                ));
        if (selected.isEmpty()) {
            return false;
        }

        boolean started = startUse(
                client,
                player,
                selected.get(),
                Action.INVISIBILITY
        );
        if (started) {
            this.invisibilityRetryAt = now + INVISIBILITY_RETRY_NANOS;
        }
        return started;
    }

    private boolean startUse(
            Minecraft client,
            LocalPlayer player,
            ConsumableSelector.Candidate<ItemStack> selected,
            Action action
    ) {
        boolean throwable = action == Action.INVISIBILITY
                && isThrowablePotion(selected.value());
        boolean rotateThrowable = throwable
                && PveManagerFeature.INSTANCE.rotate.getValue();
        if (rotateThrowable && RotationContext.isActive()) {
            return false;
        }

        boolean swappedUse =
                selected.location() != ConsumableSelector.Location.OFF_HAND;
        boolean claimed;
        if (rotateThrowable && swappedUse) {
            claimed = claim(
                    AutomationResource.INVENTORY,
                    AutomationResource.ROTATION,
                    AutomationResource.MOVEMENT,
                    AutomationResource.SCREEN
            );
        } else if (rotateThrowable) {
            claimed = claim(
                    AutomationResource.INVENTORY,
                    AutomationResource.ROTATION
            );
        } else if (swappedUse) {
            claimed = claim(
                    AutomationResource.INVENTORY,
                    AutomationResource.MOVEMENT,
                    AutomationResource.SCREEN
            );
        } else {
            claimed = claim(AutomationResource.INVENTORY);
        }
        if (!claimed) {
            return false;
        }

        if (rotateThrowable) {
            RotationContext.setRotation(player.getYRot(), 90.0F);
            this.rotationApplied = true;
        }

        boolean heldUse = action == Action.EATING || selected.value().is(Items.POTION);
        int directDelay = throwable
                && selected.location() == ConsumableSelector.Location.OFF_HAND
                ? 1
                : 0;
        if (!this.useController.start(
                client,
                player,
                selected,
                heldUse,
                directDelay
        )) {
            finishTransaction();
            return false;
        }

        this.activeAction = action;
        return true;
    }

    private void keepThrowableRotation(LocalPlayer player) {
        if (this.rotationApplied && player != null) {
            RotationContext.setRotation(player.getYRot(), 90.0F);
        }
    }

    private void cancelActive(Minecraft client, LocalPlayer player) {
        this.useController.cancel(client, player);
        finishTransaction();
    }

    private void finishTransaction() {
        if (this.rotationApplied) {

            if (owns(AutomationResource.ROTATION)
                    || !PveAutomationCoordinator.INSTANCE.isClaimed(
                            AutomationResource.ROTATION
                    )) {
                RotationContext.clear();
            }
            this.rotationApplied = false;
        }
        this.activeAction = null;
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    private boolean combatActive() {
        AuraFeature aura = AuraFeature.getMarkerFeature();
        if (aura != null && aura.getCurrentTarget() != null) {
            return true;
        }
        return PveAutomationCoordinator.INSTANCE.isClaimedByOther(
                this,
                AutomationResource.COMBAT
        ) || PveAutomationCoordinator.INSTANCE.isClaimedByOther(
                this,
                AutomationResource.ROTATION
        );
    }

    private static boolean isWorldUsable(Minecraft client, LocalPlayer player) {
        return player != null
                && client.level != null
                && client.gameMode != null
                && player.isAlive()
                && !player.isSpectator();
    }

    private static ConsumableInventory.Profile foodProfile(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);

        if (food == null || stack.is(Items.DRIED_KELP)) {
            return null;
        }

        ConsumableSelector.Kind kind;
        if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            kind = ConsumableSelector.Kind.ENCHANTED_GOLDEN_APPLE;
        } else if (stack.is(Items.GOLDEN_APPLE)) {
            kind = ConsumableSelector.Kind.GOLDEN_APPLE;
        } else {
            kind = ConsumableSelector.Kind.FOOD;
        }
        return new ConsumableInventory.Profile(
                kind,
                food.nutrition(),
                food.saturation(),
                hasNoHarmfulFoodEffect(stack)
        );
    }

    private static ConsumableInventory.Profile invisibilityProfile(ItemStack stack) {
        if (!stack.is(Items.POTION)
                && !stack.is(Items.SPLASH_POTION)
                && !stack.is(Items.LINGERING_POTION)) {
            return null;
        }

        boolean cataloguedInvisibility =
                DonItems.FunTime.ENHANCED_INVISIBILITY_POTION.matches(stack);
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        int effectCount = 0;
        boolean invisibility = false;
        if (contents != null) {
            for (MobEffectInstance effect : contents.getAllEffects()) {
                effectCount++;
                invisibility |= effect.is(MobEffects.INVISIBILITY);
            }
        }

        if (!cataloguedInvisibility && !(invisibility && effectCount == 1)) {
            return null;
        }
        return new ConsumableInventory.Profile(
                ConsumableSelector.Kind.INVISIBILITY_POTION,
                0,
                0.0F,
                true
        );
    }

    private static boolean hasNoHarmfulFoodEffect(ItemStack stack) {
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return true;
        }
        for (var effect : consumable.onConsumeEffects()) {
            if (effect instanceof ApplyStatusEffectsConsumeEffect statusEffects) {
                for (MobEffectInstance statusEffect : statusEffects.effects()) {
                    if (!statusEffect.getEffect().value().isBeneficial()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isThrowablePotion(ItemStack stack) {
        return stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
    }

    private static int locationRank(ConsumableSelector.Location location) {
        return switch (location) {
            case OFF_HAND -> 0;
            case MAIN_HAND -> 1;
            case HOTBAR -> 2;
            case INVENTORY -> 3;
        };
    }
}
