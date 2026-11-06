package win.furryhook.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import win.furryhook.context.RotationContext;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.SwingAnimationFeature;
import win.furryhook.feature.impl.visual.ViewModelFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private boolean furryhook$customSwingAnimation;

    @Inject(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER)
    )
    private void applyViewModelOffset(
            AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equippedProgress,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            CallbackInfo ci
    ) {
        ViewModelFeature viewModel = FeatureManager.INSTANCE.getEnabled(ViewModelFeature.class);
        if (viewModel == null || stack.isEmpty()
                || (player.isUsingItem() && player.getUsedItemHand() == hand)) {
            return;
        }
        if (hand == InteractionHand.MAIN_HAND && stack.getItem() instanceof CrossbowItem) {
            return;
        }
        poseStack.translate(viewModel.offsetX(hand), viewModel.offsetY(hand), viewModel.offsetZ(hand));
    }

    /**
     * Wraps the call site rather than injecting into {@code renderPlayerArm} itself, so the
     * offset is applied to the caller's PoseStack right before the operation runs - it still
     * lands even if another mod redirects that call away from the vanilla method body.
     */
    @WrapOperation(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;)V")
    )
    private void applyViewModelOffsetToBareHand(
            ItemInHandRenderer renderer,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float pitch,
            float equippedProgress,
            HumanoidArm arm,
            Operation<Void> original
    ) {
        ViewModelFeature viewModel = FeatureManager.INSTANCE.getEnabled(ViewModelFeature.class);
        if (viewModel != null) {
            InteractionHand hand = resolveHand(arm);
            poseStack.translate(viewModel.offsetX(hand), viewModel.offsetY(hand), viewModel.offsetZ(hand));
        }
        original.call(renderer, poseStack, collector, light, pitch, equippedProgress, arm);
    }

    private InteractionHand resolveHand(HumanoidArm arm) {
        LocalPlayer player = this.minecraft.player;
        HumanoidArm mainArm = player != null ? player.getMainArm() : HumanoidArm.RIGHT;
        return arm == mainArm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    @WrapOperation(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V")
    )
    private void applyCustomSwingTransform(
            ItemInHandRenderer renderer,
            PoseStack poseStack,
            HumanoidArm arm,
            float equipProgress,
            Operation<Void> original,
            AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress
    ) {
        SwingAnimationFeature swing = FeatureManager.INSTANCE.getEnabled(SwingAnimationFeature.class);
        boolean usingThisHand = player.isUsingItem() && player.getUsedItemHand() == hand;
        if (!usingThisHand && hand == InteractionHand.MAIN_HAND && swing != null && swing.shouldApply(swingProgress)) {
            swing.apply(poseStack, arm, swingProgress, equipProgress);
            furryhook$customSwingAnimation = true;
        } else {
            furryhook$customSwingAnimation = false;
            original.call(renderer, poseStack, arm, equipProgress);
        }
        if (swing != null && !usingThisHand && swing.spinAppliesTo(arm)) {
            swing.applySpin(poseStack);
        }
    }

    @WrapOperation(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V")
    )
    private void skipVanillaSwingWhenCustom(
            ItemInHandRenderer renderer,
            float swingProgress,
            PoseStack poseStack,
            int direction,
            HumanoidArm arm,
            Operation<Void> original
    ) {
        if (!furryhook$customSwingAnimation) {
            original.call(renderer, swingProgress, poseStack, direction, arm);
        }
    }

    @Redirect(
            method = "submitHandsWithItems",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot(F)F")
    )
    private float useFreeLookPitch(LocalPlayer player, float partialTick) {
        if (RotationContext.isActive()) {
            win.furryhook.feature.impl.combat.AuraFeature aura = win.furryhook.feature.FeatureManager.INSTANCE.getEnabled(win.furryhook.feature.impl.combat.AuraFeature.class);
            if (aura != null && aura.clientLook.getValue()) {
                return player.getXRot(partialTick);
            }
            return RotationContext.getFreePitch();
        }

        return player.getXRot(partialTick);
    }

    @Redirect(
            method = "submitHandsWithItems",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewXRot(F)F")
    )
    private float useCameraPitch(LocalPlayer player, float partialTick) {
        return this.minecraft.gameRenderer.mainCamera().xRot();
    }

    @Redirect(
            method = "submitHandsWithItems",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewYRot(F)F")
    )
    private float useCameraYaw(LocalPlayer player, float partialTick) {
        return this.minecraft.gameRenderer.mainCamera().yRot();
    }
}
