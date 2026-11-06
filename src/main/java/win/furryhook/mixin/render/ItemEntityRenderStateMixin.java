package win.furryhook.mixin.render;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import win.furryhook.utils.render.ItemEntityRenderStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements ItemEntityRenderStateAccess {
    @Unique
    private boolean onGround;

    @Override
    public boolean isOnGround() {
        return onGround;
    }

    @Override
    public void setOnGround(boolean onGround) {
        onGround = onGround;
    }
}
