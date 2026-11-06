package win.furryhook.event.events.game;

import lombok.Getter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import win.furryhook.event.Event;

/**
 * Fires whenever the local player issues an attack via {@code MultiPlayerGameMode.attack},
 * regardless of whether it originated from a manual click, Aura, or a FakePlayer hit -
 * unlike {@link AttackEvent}, which only covers manual {@code Minecraft.startAttack} clicks.
 */
@Getter
public final class EntityAttackEvent extends Event {
    private Player player;
    private Entity target;

    public EntityAttackEvent set(Player player, Entity target) {
        this.player = player;
        this.target = target;
        return this;
    }
}
