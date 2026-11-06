package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.render.FinalGuiRenderEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * AutoTotem - порт AutoTotemV2 (elizz). Автоматически экипирует тотем в оффханд
 * с фазами: Default свапит сразу, Legit гасит движение, свапает и плавно возвращает.
 */
public final class AutoTotemFeature extends Feature {

    public final ModeSetting mode = register(new ModeSetting("Режим", "Default", "Default", "Legit"));
    public final BooleanSetting crystal = register(new BooleanSetting("Кристалы", true));
    public final NumberSetting healthThreshold = register(new NumberSetting("Порог здоровья", 4.5, 1.0, 20.0, 0.5, ""));
    public final NumberSetting elytraHealth = register(new NumberSetting("Здоровье на элитре", 8.5, 1.0, 20.0, 0.5, ""));
    public final NumberSetting crystalDistance = register(new NumberSetting("Дистанция до кристалла", 4.0, 1.0, 6.0, 0.5, "").visibleWhen(crystal::getValue));
    public final NumberSetting crystalMaxHealth = register(new NumberSetting("Здоровье с шаром", 10.0, 1.0, 20.0, 0.5, "").visibleWhen(crystal::getValue));
    public final BooleanSetting fallCheck = register(new BooleanSetting("Падение", true));
    public final BooleanSetting saveTaliks = register(new BooleanSetting("Сейв таликов", true));
    public final BooleanSetting returnItem = register(new BooleanSetting("Возвращать предмет", true));
    public final NumberSetting returnDelay = register(new NumberSetting("Задержка возврата", 500.0, 0.0, 2000.0, 50.0, "ms"));
    public final BooleanSetting drawCount = register(new BooleanSetting("Отображать количество", true));

    private int savedSlot = -1;
    private int totemSlot = -1;
    private int savedItemSlot = -1;
    private long actionStartTime = 0L;
    private long totemEquipTime = 0L;
    private boolean keysOverridden = false;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private Phase phase = Phase.READY;

    private enum Phase {
        READY,
        SLOWING_DOWN,
        SWAP_TOTEM,
        AWAIT_SWITCH,
        RESTORE_SLOT,
        SPEEDING_UP,
        FINISH,
        RETURN_SLOWING_DOWN,
        RETURN_SWAP,
        RETURN_AWAIT,
        RETURN_SPEEDING_UP
    }

    public AutoTotemFeature() {
        super("AutoTotem", "Автоматически экипирует тотем с фазами", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        if (phase != Phase.READY) {
            execute(mc, player);
            return;
        }

        float health = player.getHealth() + player.getAbsorptionAmount();
        boolean shouldEquip = false;
        boolean isFlying = player.isFallFlying();

        if (isFlying && health <= elytraHealth.getValue()) {
            shouldEquip = true;
        } else if (health <= healthThreshold.getValue()) {
            shouldEquip = true;
        } else if (fallCheck.getValue() && player.fallDistance > 10.0F) {
            shouldEquip = true;
        } else if (crystal.getValue() && crystalCheck(mc, player)) {
            shouldEquip = true;
        }

        if (shouldEquip) {
            tryEquipTotem(mc, player);
        } else if (savedItemSlot != -1 && returnItem.getValue()) {
            long timeSinceEquip = System.currentTimeMillis() - totemEquipTime;
            if (timeSinceEquip >= returnDelay.getValue()) {
                if (mode.is("Legit")) {
                    startLegitReturnItem(mc);
                } else {
                    executeDefaultReturnItem(mc, player);
                }
            }
        }
    }

    @EventTarget
    public void onFinalGuiRender(FinalGuiRenderEvent event) {
        if (!drawCount.getValue()) {
            return;
        }
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        int totemCount = getTotemCount(player);
        if (totemCount <= 0) {
            return;
        }

        GuiGraphicsExtractor extractor = event.getGuiGraphicsExtractor();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        float x = screenWidth / 2.0F + 10.0F;
        float y = screenHeight - 264;

        drawItem(mc, extractor, new ItemStack(Items.TOTEM_OF_UNDYING), x, y, 16.0F);

        String countText = totemCount + "x";
        extractor.text(mc.font, countText, Math.round(x + 17.0F), Math.round(y + 4.0F), 0xFFFFFFFF, true);
    }

    @Override
    protected void onDisable() {
        resetState();
        savedItemSlot = -1;
    }

    // ==================== EQUIP ====================

    private void tryEquipTotem(Minecraft mc, LocalPlayer player) {
        if (phase != Phase.READY) {
            return;
        }
        if (isTotemInOffhand(player)) {
            return;
        }

        savedSlot = player.getInventory().getSelectedSlot();

        // Поиск в хотбаре
        totemSlot = findTotemInHotbar(player);
        if (totemSlot != -1) {
            savedItemSlot = totemSlot;
            if (mode.is("Legit")) {
                startLegitEquip(mc);
            } else {
                executeDefaultSwap(mc, player);
            }
            return;
        }

        // Поиск в инвентаре (с предпочтением таликов без чар)
        totemSlot = findTotemInInventory(player);
        if (totemSlot != -1) {
            if (savedItemSlot == -1) {
                savedItemSlot = totemSlot;
            }
            if (mode.is("Legit")) {
                startLegitEquip(mc);
            } else {
                executeDefaultSwap(mc, player);
            }
        }
    }

    private void executeDefaultSwap(Minecraft mc, LocalPlayer player) {
        if (totemSlot < 0) {
            resetState();
            return;
        }

        swapSlotWithOffhand(mc, player, totemSlot);
        totemEquipTime = System.currentTimeMillis();

        if (savedSlot >= 0) {
            player.getInventory().setSelectedSlot(savedSlot);
        }

        resetState();
    }

    private void executeDefaultReturnItem(Minecraft mc, LocalPlayer player) {
        if (savedItemSlot >= 0) {
            swapSlotWithOffhand(mc, player, savedItemSlot);
        }
        savedItemSlot = -1;
    }

    // ==================== LEGIT PHASE MACHINE ====================

    private void startLegitEquip(Minecraft mc) {
        captureKeyStates(mc);
        phase = Phase.SLOWING_DOWN;
        actionStartTime = System.currentTimeMillis();
        keysOverridden = false;
    }

    private void startLegitReturnItem(Minecraft mc) {
        captureKeyStates(mc);
        phase = Phase.RETURN_SLOWING_DOWN;
        actionStartTime = System.currentTimeMillis();
        keysOverridden = false;
    }

    private void execute(Minecraft mc, LocalPlayer player) {
        if (mc.player == null) {
            resetState();
            return;
        }

        long elapsed = System.currentTimeMillis() - actionStartTime;

        switch (phase) {
            case SLOWING_DOWN -> {
                releaseKeys(mc);
                if (player.isSprinting()) {
                    player.setSprinting(false);
                }
                if (elapsed > 1L) {
                    phase = Phase.SWAP_TOTEM;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case SWAP_TOTEM -> {
                if (elapsed > 1L) {
                    if (totemSlot < 0) {
                        resetState();
                        return;
                    }
                    swapSlotWithOffhand(mc, player, totemSlot);
                    phase = Phase.AWAIT_SWITCH;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case AWAIT_SWITCH -> {
                if (isTotemInOffhand(player) || elapsed > 60L) {
                    totemEquipTime = System.currentTimeMillis();
                    phase = Phase.RESTORE_SLOT;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case RESTORE_SLOT -> {
                if (elapsed > 66L) {
                    if (savedSlot >= 0 && savedSlot <= 8) {
                        player.getInventory().setSelectedSlot(savedSlot);
                    }
                    if (mode.is("Legit")) {
                        if (keysOverridden) {
                            restoreKeyStates(mc);
                        }
                        actionStartTime = System.currentTimeMillis();
                        phase = Phase.SPEEDING_UP;
                    } else {
                        phase = Phase.FINISH;
                    }
                }
            }
            case SPEEDING_UP -> {
                // В этой версии нет float-ввода (Input - record): клавиши уже восстановлены,
                // так что плавный forward-lerp из референса здесь не нужен.
                if (elapsed > 25L) {
                    phase = Phase.FINISH;
                }
            }
            case FINISH -> resetState();
            case RETURN_SLOWING_DOWN -> {
                releaseKeys(mc);
                if (player.isSprinting()) {
                    player.setSprinting(false);
                }
                if (elapsed > 1L) {
                    phase = Phase.RETURN_SWAP;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case RETURN_SWAP -> {
                if (elapsed > 1L) {
                    if (savedItemSlot >= 0) {
                        swapSlotWithOffhand(mc, player, savedItemSlot);
                    }
                    savedItemSlot = -1;
                    phase = Phase.RETURN_AWAIT;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case RETURN_AWAIT -> {
                if (elapsed > 25L) {
                    if (keysOverridden) {
                        restoreKeyStates(mc);
                    }
                    actionStartTime = System.currentTimeMillis();
                    phase = Phase.RETURN_SPEEDING_UP;
                }
            }
            case RETURN_SPEEDING_UP -> {
                if (elapsed > 25L) {
                    phase = Phase.FINISH;
                }
            }
        }
    }

    private void captureKeyStates(Minecraft mc) {
        wasForwardPressed = mc.options.keyUp.isDown();
        wasBackPressed = mc.options.keyDown.isDown();
        wasLeftPressed = mc.options.keyLeft.isDown();
        wasRightPressed = mc.options.keyRight.isDown();
        wasJumpPressed = mc.options.keyJump.isDown();
    }

    private void releaseKeys(Minecraft mc) {
        if (keysOverridden) {
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        keysOverridden = true;
    }

    private void restoreKeyStates(Minecraft mc) {
        mc.options.keyUp.setDown(wasForwardPressed && mc.options.keyUp.isDown());
        mc.options.keyDown.setDown(wasBackPressed && mc.options.keyDown.isDown());
        mc.options.keyLeft.setDown(wasLeftPressed && mc.options.keyLeft.isDown());
        mc.options.keyRight.setDown(wasRightPressed && mc.options.keyRight.isDown());
        mc.options.keyJump.setDown(wasJumpPressed && mc.options.keyJump.isDown());
        keysOverridden = false;
    }

    // ==================== CHECKS ====================

    private boolean crystalCheck(Minecraft mc, LocalPlayer player) {
        double radius = 6.5;
        double myY = player.getY();

        // Игроки с кристаллом/якорем в руках
        for (Player other : mc.level.players()) {
            if (other == player) {
                continue;
            }
            double distance = player.distanceTo(other);
            if (distance > radius) {
                continue;
            }

            boolean hasCrystal = other.getMainHandItem().is(Items.END_CRYSTAL)
                    || other.getOffhandItem().is(Items.END_CRYSTAL)
                    || other.getMainHandItem().is(Items.RESPAWN_ANCHOR)
                    || other.getOffhandItem().is(Items.RESPAWN_ANCHOR);
            if (hasCrystal) {
                double dy = myY - other.getY();
                if (dy > 0.3 && dy <= 6.0) {
                    return true;
                }
            }
        }

        // Существующие кристаллы
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, player.getBoundingBox().inflate(radius))) {
            double distance = player.position().distanceTo(crystal.getBoundingBox().getCenter());
            if (distance <= crystalDistance.getValue()) {
                if (player.getY() >= crystal.getY()) {
                    ItemStack offhand = player.getOffhandItem();
                    if (!offhand.is(Items.SHIELD)
                            || !offhand.isEnchanted()
                            || player.getHealth() <= crystalMaxHealth.getValue()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isTotemInOffhand(LocalPlayer player) {
        return player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private int findTotemInHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private int findTotemInInventory(LocalPlayer player) {
        // Сначала талики без чар
        if (saveTaliks.getValue()) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(Items.TOTEM_OF_UNDYING) && !stack.isEnchanted()) {
                    return i;
                }
            }
        }

        // Не нашли без чар - берём любой
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    private int getTotemCount(LocalPlayer player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // ==================== UTILITY ====================

    private void swapSlotWithOffhand(Minecraft mc, LocalPlayer player, int slot) {
        if (mc.gameMode == null || player.containerMenu == null) {
            return;
        }
        int slotIndex = slot;
        if (slotIndex >= 0 && slotIndex <= 8) {
            slotIndex += 36;
        }
        mc.gameMode.handleContainerInput(
                player.containerMenu.containerId,
                slotIndex,
                40,
                ContainerInput.SWAP,
                player
        );
    }

    private void drawItem(Minecraft mc, GuiGraphicsExtractor extractor, ItemStack stack, float x, float y, float size) {
        if (stack.isEmpty()) {
            return;
        }
        float guiScale = (float) mc.getWindow().getGuiScale();
        float itemX = Math.round(x * guiScale) / guiScale;
        float itemY = Math.round(y * guiScale) / guiScale;
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(itemX, itemY);
        pose.scale(size / 16.0F);
        extractor.item(mc.player, stack, 0, 0, 0);
        pose.popMatrix();
    }

    private void resetState() {
        if (keysOverridden) {
            restoreKeyStates(Minecraft.getInstance());
        }
        totemSlot = -1;
        savedSlot = -1;
        actionStartTime = 0L;
        phase = Phase.READY;
    }
}