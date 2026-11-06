package win.furryhook.feature.impl.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.utils.InputGuard;
import win.furryhook.utils.text.ChatUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ServerHelperFeature extends Feature {
    
    // Binds for FunTime items
    public final InputBindSetting ftSnowball = register(new InputBindSetting("Снежок заморозка", InputBindSetting.UNBOUND));
    public final InputBindSetting ftGodsAura = register(new InputBindSetting("Божья аура", InputBindSetting.UNBOUND));
    public final InputBindSetting ftTrap = register(new InputBindSetting("Трапка", InputBindSetting.UNBOUND));
    public final InputBindSetting ftPlast = register(new InputBindSetting("Пласт", InputBindSetting.UNBOUND));
    public final InputBindSetting ftClearDust = register(new InputBindSetting("Явная пыль", InputBindSetting.UNBOUND));
    public final InputBindSetting ftFireTornado = register(new InputBindSetting("Огненный смерч", InputBindSetting.UNBOUND));
    public final InputBindSetting ftDisorientation = register(new InputBindSetting("Дезориентация", InputBindSetting.UNBOUND));
    public final InputBindSetting ftStrengthPotion = register(new InputBindSetting("Зелье силы", InputBindSetting.UNBOUND));
    public final InputBindSetting ftSpeedPotion = register(new InputBindSetting("Зелье скорости", InputBindSetting.UNBOUND));
    public final InputBindSetting ftWindCharge = register(new InputBindSetting("Заряд ветра", InputBindSetting.UNBOUND));

    private final List<ItemBind> itemBinds = new ArrayList<>();
    
    // Swap state
    private Item pendingItem = null;
    private int tickTimer = 0;
    private int itemSlot = -1;
    private int originalSlot = -1;
    
    // Movement state
    private boolean savedForward, savedBack, savedLeft, savedRight, savedJump;

    public ServerHelperFeature() {
        super("ServerHelper", "FunTime helper for quick item usage", FeatureCategory.MISC, BindSetting.UNBOUND);
        initializeBinds();
    }

    private void initializeBinds() {
        itemBinds.add(new ItemBind(ftSnowball, Items.SNOWBALL));
        itemBinds.add(new ItemBind(ftGodsAura, Items.PHANTOM_MEMBRANE));
        itemBinds.add(new ItemBind(ftTrap, Items.NETHERITE_SCRAP));
        itemBinds.add(new ItemBind(ftPlast, Items.DRIED_KELP));
        itemBinds.add(new ItemBind(ftClearDust, Items.SUGAR));
        itemBinds.add(new ItemBind(ftFireTornado, Items.FIRE_CHARGE));
        itemBinds.add(new ItemBind(ftDisorientation, Items.ENDER_EYE));
        itemBinds.add(new ItemBind(ftStrengthPotion, stack -> stack.is(Items.SPLASH_POTION) && stack.getHoverName().getString().contains("Силы")));
        itemBinds.add(new ItemBind(ftSpeedPotion, stack -> stack.is(Items.SPLASH_POTION) && stack.getHoverName().getString().contains("Скорости")));
        itemBinds.add(new ItemBind(ftWindCharge, Items.WIND_CHARGE));
    }

    @Override
    protected void onDisable() {
        resetSwapState();
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && !InputGuard.isBindingBlocked()) {
            for (ItemBind bind : itemBinds) {
                if (bind.setting.matches(event.getKey()) && pendingItem == null) {
                    queueItemUse(bind);
                    event.cancel();
                    break;
                }
            }
        }
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && !InputGuard.isBindingBlocked()) {
            for (ItemBind bind : itemBinds) {
                if (bind.setting.matchesMouse(event.getButton()) && pendingItem == null) {
                    queueItemUse(bind);
                    event.cancel();
                    break;
                }
            }
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        
        if (player == null || pendingItem == null) {
            return;
        }

        tickTimer++;

        // Tick 1: Save movement state and stop moving
        if (tickTimer == 1) {
            saveAndStopMovement(mc);
            itemSlot = findItemSlot(player, pendingItem);
            
            if (itemSlot == -1) {
                ChatUtil.error("Предмет не найден");
                resetSwapState();
                return;
            }
        }

        int selectedSlot = player.getInventory().getSelectedSlot();
        int syncId = player.containerMenu.containerId;

        switch (tickTimer) {
            case 2 -> {
                // Swap item to hand
                if (itemSlot >= 9) {
                    mc.gameMode.handleContainerInput(syncId, itemSlot, selectedSlot, ContainerInput.SWAP, player);
                } else {
                    player.getInventory().setSelectedSlot(itemSlot);
                }
            }
            case 3 -> {
                // Use item
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
            }
            case 4 -> {
                // Swap back
                if (itemSlot >= 9) {
                    mc.gameMode.handleContainerInput(syncId, itemSlot, selectedSlot, ContainerInput.SWAP, player);
                } else {
                    player.getInventory().setSelectedSlot(originalSlot);
                }
            }
            case 5 -> {
                // Restore movement
                resetSwapState();
            }
        }
    }

    private void queueItemUse(ItemBind bind) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        
        if (player == null || mc.gui.screen() != null) {
            return;
        }

        int slot = findItemSlot(player, bind);
        if (slot == -1) {
            ChatUtil.error("Предмет не найден");
            return;
        }

        originalSlot = player.getInventory().getSelectedSlot();
        pendingItem = bind.item != null ? bind.item : Items.SPLASH_POTION;
        tickTimer = 0;
    }

    private void saveAndStopMovement(Minecraft mc) {
        savedForward = mc.options.keyUp.isDown();
        savedBack = mc.options.keyDown.isDown();
        savedLeft = mc.options.keyLeft.isDown();
        savedRight = mc.options.keyRight.isDown();
        savedJump = mc.options.keyJump.isDown();

        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private void resetSwapState() {
        Minecraft mc = Minecraft.getInstance();
        
        if (mc.options != null) {
            mc.options.keyUp.setDown(savedForward);
            mc.options.keyDown.setDown(savedBack);
            mc.options.keyLeft.setDown(savedLeft);
            mc.options.keyRight.setDown(savedRight);
            mc.options.keyJump.setDown(savedJump);
        }

        pendingItem = null;
        itemSlot = -1;
        tickTimer = 0;
    }

    private int findItemSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 45; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int findItemSlot(LocalPlayer player, ItemBind bind) {
        if (bind.predicate != null) {
            for (int i = 0; i < 45; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (bind.predicate.test(stack)) {
                    return i;
                }
            }
        } else if (bind.item != null) {
            return findItemSlot(player, bind.item);
        }
        return -1;
    }

    private static class ItemBind {
        final InputBindSetting setting;
        final Item item;
        final Predicate<ItemStack> predicate;

        ItemBind(InputBindSetting setting, Item item) {
            this.setting = setting;
            this.item = item;
            this.predicate = null;
        }

        ItemBind(InputBindSetting setting, Predicate<ItemStack> predicate) {
            this.setting = setting;
            this.item = null;
            this.predicate = predicate;
        }
    }
}
