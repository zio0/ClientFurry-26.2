package win.furryhook;

import net.fabricmc.api.ModInitializer;
import win.furryhook.command.CommandManager;
import win.furryhook.event.EventManager;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.ClientStartEvent;
import win.furryhook.event.events.lifecycle.ShutdownEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.FeatureManager;
import win.furryhook.utils.FriendManager;
import win.furryhook.hud.NotificationsElement;
import win.furryhook.menu.MenuKeyHandler;
import win.furryhook.menu.MenuOverlayRenderHandler;
import win.furryhook.menu.i18n.UiLanguage;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PvpStateTracker;
import win.furryhook.utils.AccountSwitcher;
import win.furryhook.utils.DiscordPresence;
import win.furryhook.utils.combat.ServerSprintTracker;
import win.furryhook.utils.combat.ServerTickSync;
import win.furryhook.utils.combat.neuro.NeuroDataset;
import win.furryhook.utils.ScreenNbtParser;
import win.furryhook.utils.inventory.DropAllInventoryController;
import win.furryhook.utils.inventory.InventorySwap;
import win.furryhook.utils.render.EntityEspStateCache;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.gui.GuiTexture;
import win.furryhook.utils.render.world.WorldEffects;

public class Furryhook implements ModInitializer {
    private final MenuKeyHandler menuKeyHandler = new MenuKeyHandler();
    private final MenuOverlayRenderHandler menuOverlayRenderHandler = new MenuOverlayRenderHandler();

    @Override
    public void onInitialize() {
        UiLanguage.loadSaved();
        win.furryhook.menu.core.MenuOverlay.loadSavedTheme();
        FriendManager.INSTANCE.initialize();
        FeatureManager.INSTANCE.initialize();
        CommandManager.INSTANCE.initialize();
        NeuroDataset.INSTANCE.loadDefault();
        WorldEffects.bootstrap();
        EventManager.subscribe(FeatureManager.INSTANCE);
        EventManager.subscribe(CommandManager.INSTANCE);
        EventManager.subscribe(FriendManager.INSTANCE);
        EventManager.subscribe(new NotificationsElement.ToggleListener());
        EventManager.subscribe(menuKeyHandler);
        EventManager.subscribe(menuOverlayRenderHandler);
        EventManager.subscribe(InventorySwap.INSTANCE);
        EventManager.subscribe(DropAllInventoryController.INSTANCE);
        EventManager.subscribe(ServerTickSync.INSTANCE);
        EventManager.subscribe(ServerSprintTracker.INSTANCE);
        EventManager.subscribe(PveAutomationCoordinator.INSTANCE);
        EventManager.subscribe(PvpStateTracker.INSTANCE);
        EventManager.subscribe(ScreenNbtParser.INSTANCE);
        EventManager.subscribe(DiscordPresence.INSTANCE);
        EventManager.subscribe(this);
    }

    @EventTarget
    public void onClientStart(ClientStartEvent event) {
        AccountSwitcher.applyStartupAccount();

        GuiTexture.prewarm(Textures.all());
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {

        EntityEspStateCache.clear();
    }

    @EventTarget
    public void onShutdown(ShutdownEvent event) {

        NeuroDataset.INSTANCE.saveIfDirty();
    }
}
