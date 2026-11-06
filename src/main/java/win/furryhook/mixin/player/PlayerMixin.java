package win.furryhook.mixin.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import win.furryhook.feature.impl.movement.NoPushFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "makeStuckInBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void cancelBlockPush(BlockState state, Vec3 multiplier, CallbackInfo ci) {
        if (NoPushFeature.shouldCancelBlockPush((Player) (Object) this, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "isPushedByFluid()Z", at = @At("HEAD"), cancellable = true)
    private void cancelFluidPush(CallbackInfoReturnable<Boolean> cir) {
        if (NoPushFeature.shouldCancelFluidPush((Player) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
