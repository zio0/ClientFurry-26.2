package win.furryhook.mixin.accessor;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The rotation the client last actually sent. {@code sendPosition} writes these after every
 * movement packet, so they are exactly what the server — and Grim — is holding when an attack
 * arrives, which is the angle an attack has to be validated against.
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("yRotLast")
    float getYRotLast();

    @Accessor("xRotLast")
    float getXRotLast();
}
