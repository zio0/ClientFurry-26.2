package win.furryhook.mixin.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    void setDestroyDelay(int destroyDelay);

    /**
     * Forces the lazy carried-slot sync. Vanilla only sends the
     * {@code ServerboundSetCarriedItemPacket} from here on the next game action or tick,
     * so after a manual {@code setSelectedSlot(...)} restore the server never hears about
     * the change until something else happens. Calling this right after a restore keeps
     * client and server hotbar slots in step — the thing Grim checks.
     */
    @Invoker("ensureHasSentCarriedItem")
    void invokerEnsureHasSentCarriedItem();

    @Accessor("destroyDelay")
    int getDestroyDelay();

    /**
     * The slot the client believes the server last heard about. Anything sending
     * {@code ServerboundSetCarriedItemPacket} by hand has to keep this in step, or the
     * lazy sync in {@code ensureHasSentCarriedItem} works off a stale comparison and
     * silently skips the packet that mattered.
     */
    @Accessor("carriedIndex")
    void setCarriedIndex(int carriedIndex);
}
