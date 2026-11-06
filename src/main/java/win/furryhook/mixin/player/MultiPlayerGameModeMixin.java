package win.furryhook.mixin.player;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventManager;
import win.furryhook.event.Events;
import win.furryhook.event.events.game.EntityAttackEvent;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.player.FakePlayerFeature;
import win.furryhook.feature.impl.visual.ParticlesFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Player player, Entity target, CallbackInfo ci) {
        if (player != MinecraftContext.mc.player) {
            return;
        }

        if (EventManager.hasListeners(EntityAttackEvent.class)) {
            EventManager.call(Events.ENTITY_ATTACK.set(player, target));
        }

        ParticlesFeature particles = ParticlesFeature.getEnabled();
        if (particles != null) {
            particles.onAttack(target);
        }

        FakePlayerFeature fakePlayer = FeatureManager.INSTANCE.getFeature(FakePlayerFeature.class);
        if (fakePlayer != null && fakePlayer.isFakePlayer(target)) {
            fakePlayer.registerHit();
            ci.cancel();
        }
    }
}
