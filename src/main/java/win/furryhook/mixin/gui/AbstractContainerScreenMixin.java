package win.furryhook.mixin.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import win.furryhook.feature.impl.misc.AuctionHelperFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void renderAuctionRank(GuiGraphicsExtractor graphics,
                                         Slot slot,
                                         int mouseX,
                                         int mouseY,
                                         CallbackInfo ci) {
        if (slot == null || !slot.hasItem()) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        int color = AuctionHelperFeature.slotOverlayColor(
                screen.getTitle().getString(),
                slot.getItem()
        );
        if (color != 0) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void augmentAuctionTooltip(ItemStack stack,
                                             CallbackInfoReturnable<List<Component>> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        cir.setReturnValue(AuctionHelperFeature.augmentTooltip(
                screen.getTitle().getString(),
                stack,
                cir.getReturnValue()
        ));
    }
}
