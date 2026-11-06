package win.furryhook.event.events.game;

import lombok.Getter;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.Event;

@Getter
public final class ElytraPostTravelEvent extends Event {
    private Vec3 oldVelocity;

    public ElytraPostTravelEvent set(Vec3 oldVelocity) {
        this.oldVelocity = oldVelocity;
        return this;
    }

    public void setOldVelocity(Vec3 oldVelocity) {
        this.oldVelocity = oldVelocity;
    }
}
