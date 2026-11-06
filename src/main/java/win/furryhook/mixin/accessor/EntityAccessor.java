package win.furryhook.mixin.accessor;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    /** Exposes the protected shared-flag setter (flag 7 = gliding / fall-flying). */
    @Invoker("setSharedFlag")
    void invokeSetSharedFlag(int flag, boolean set);
}
