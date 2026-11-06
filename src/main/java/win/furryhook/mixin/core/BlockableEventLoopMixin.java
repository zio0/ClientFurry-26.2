package win.furryhook.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import win.furryhook.context.RenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds queued tasks back for a moment while the startup loading bar is on screen, so the animation
 * runs at a steady rate instead of being sawn apart by a resource reload landing on the same thread.
 *
 * <h2>Why this is fenced in as hard as it is</h2>
 *
 * <p>{@code runAllTasks()} is not a housekeeping call. {@link BlockableEventLoop#managedBlock} drains
 * the queue through it and spins until its condition is met — cancel the drain and the waiting thread
 * has nothing to make progress on, so it burns a core going nowhere until the deferral window closes.
 *
 * <p>The previous version cancelled on <b>every</b> {@code BlockableEventLoop} in the game. That is
 * not only the client: {@code MinecraftServer} is one too. So joining a server, which puts a loading
 * overlay up for the registry sync and the server resource pack, armed a global "execute nothing" on
 * both the client loop and the server loop, right at the point where the client is blocked waiting
 * for its player chunk to arrive through exactly those queues. That is the "стоит на загрузке
 * сервера и не отвечает" freeze — a cosmetic animation tweak stalling world load.
 *
 * <p>So now it can only ever touch the client's own loop, and only before there is a connection to
 * stall. Once the client is talking to a server or has a level, the deferral is off, unconditionally.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {
    /** Ceiling on the hold, even at startup. Nothing waits on a drained queue longer than this. */
    private static final long MAX_DEFER_MILLIS = 3000L;

    @Inject(method = "runAllTasks", at = @At("HEAD"), cancellable = true)
    private void onRunAllTasks(CallbackInfo ci) {
        if (!RenderContext.deferQueuedTasks) {
            return;
        }

        long overlayStart = RenderContext.overlayStartTime;
        if (overlayStart < 0L) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // Only the client's own loop. The integrated server is a BlockableEventLoop as well, and
        // holding its queue back stalls world tick, chunk load and the join handshake.
        if ((Object) this != mc) {
            return;
        }

        // Anything past the title screen is real work with something waiting on the far end of it:
        // the join handshake, the registry sync, the player chunk. Never defer through that.
        if (mc.level != null || mc.getConnection() != null) {
            return;
        }

        if (Util.getMillis() - overlayStart < MAX_DEFER_MILLIS) {
            ci.cancel();
        }
    }
}
