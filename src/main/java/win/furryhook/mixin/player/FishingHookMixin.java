package win.furryhook.mixin.player;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import win.furryhook.feature.impl.movement.NoPushFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Inject(method = "pullEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void cancelFishingHookPull(Entity entity, CallbackInfo ci) {
        if (NoPushFeature.shouldCancelFishingHookPull((FishingHook) (Object) this, entity)) {
            ci.cancel();
        }
    }
}
