package win.furryhook.feature.impl.misc;

import net.minecraft.world.inventory.AbstractContainerMenu;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

public final class ChestStealerFeature extends Feature {
    public final NumberSetting delay = register(new NumberSetting("Delay", 80.0, 0.0, 1000.0, 10.0, "ms"));

    private long lastMoveAt;

    public ChestStealerFeature() {
        super("ChestStealer", "Automatically takes items from containers", FeatureCategory.MISC, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (PveAutomationCoordinator.INSTANCE.isClaimed(AutomationResource.INVENTORY)
                || !InventoryUtil.isContainerScreenOpen()) {
            return;
        }
        AbstractContainerMenu menu = InventoryUtil.getOpenMenu();
        if (menu == null || menu == event.getClient().player.inventoryMenu) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastMoveAt < delay.getValue().longValue()) {
            return;
        }
        if (ContainerLootService.quickMoveFirst(menu, stack -> !stack.isEmpty())) {
            lastMoveAt = now;
        }
    }
}
