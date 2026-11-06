package win.furryhook.mixin.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import win.furryhook.mixin.accessor.AbstractContainerScreenAccessor;
import win.furryhook.utils.inventory.DropAllInventoryController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends Screen {
    protected InventoryScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addDropAllButton(CallbackInfo ci) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) this;
        Button button = Button.builder(
                        Component.literal("Выбросить всё"),
                        ignored -> DropAllInventoryController.toggle((InventoryScreen) (Object) this)
                )
                .bounds((this.width - 110) / 2, Math.max(4, accessor.getTopPos() - 24), 110, 20)
                .build();
        addRenderableWidget(button);
        DropAllInventoryController.bindButton(button);
    }
}
