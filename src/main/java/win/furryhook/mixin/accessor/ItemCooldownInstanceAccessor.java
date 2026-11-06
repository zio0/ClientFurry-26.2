package win.furryhook.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.item.ItemCooldowns$CooldownInstance")
public interface ItemCooldownInstanceAccessor {
    @Accessor("startTime")
    int furryhook$getStartTime();

    @Accessor("endTime")
    int furryhook$getEndTime();
}
