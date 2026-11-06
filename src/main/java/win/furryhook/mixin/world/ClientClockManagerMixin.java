package win.furryhook.mixin.world;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import win.furryhook.feature.impl.visual.AmbienceFeature;
import win.furryhook.feature.impl.visual.WorldTweaksFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void onGetTotalTicks(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
        if (!clock.is(WorldClocks.OVERWORLD)) {
            return;
        }

        // Ambience holds the clock flat at its preset — no easing, the way javelin does it — and
        // takes precedence over WorldTweaks' animated override so the two never fight over the tick.
        AmbienceFeature ambience = AmbienceFeature.getEnabled();
        if (ambience != null) {
            cir.setReturnValue(ambience.getTargetTime());
            return;
        }

        WorldTweaksFeature worldTweaks = WorldTweaksFeature.getEnabled();
        if (worldTweaks != null && worldTweaks.changeTime.getValue()) {
            cir.setReturnValue(worldTweaks.getCustomTime());
        }
    }
}
