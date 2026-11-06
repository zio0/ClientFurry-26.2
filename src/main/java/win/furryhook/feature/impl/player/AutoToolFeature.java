package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.CrystalAuraFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.utils.inventory.InventorySwap;

public final class AutoToolFeature extends Feature {
    private static final int HOTBAR_SIZE = 9;
    private static final int INVENTORY_SIZE = 36;

    // Состояния инвентарного свапа (инструмент из инвентаря -> ведущая рука -> обратно)
    private static final int ST_IDLE = 0;
    private static final int ST_SWAPPING_IN = 1;
    private static final int ST_READY = 2;
    private static final int ST_SWAPPING_OUT = 3;

    public final BooleanSetting useInventory = register(new BooleanSetting("Use Inventory", true));
    public final ModeSetting mode = register(new ModeSetting(
            "Mode", "Silent", "Silent", "Normal"
    ));

    private int originalHotbarSlot = -1;
    private int swappedInventorySlot = -1;
    private int silentServerSlot = -1;
    private int swapState = ST_IDLE;

    public AutoToolFeature() {
        super("AutoTool", "Picks the best tool for the targeted block", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player != null) {
            if (this.swappedInventorySlot != -1 && this.originalHotbarSlot != -1 && !InventorySwap.isBusy()) {
                // Инструмент уже в хотбаре — глобальный InventorySwap доработает даже без нас.
                InventorySwap.moveToHotbar(this.swappedInventorySlot, this.originalHotbarSlot);
            }
            restoreSilentServerSlot(player);
            if (this.originalHotbarSlot != -1) {
                player.getInventory().setSelectedSlot(this.originalHotbarSlot);
            }
        }
        this.originalHotbarSlot = -1;
        this.swappedInventorySlot = -1;
        this.silentServerSlot = -1;
        this.swapState = ST_IDLE;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || player.isCreative() || client.gui.screen() != null) {
            restoreNow(player);
            return;
        }

        boolean breaking = crystalAuraBreakState(client) != null || isBreakingBlock(client);

        // Глобальный свап (наш или чужой) ещё в полёте — ждём усадки. Пока ломаем,
        // держим destroyDelay, чтобы ломка не началась до применения клика (Грим).
        if (InventorySwap.isBusy()) {
            if (breaking) {
                gateDestroy();
            }
            return;
        }

        // Возврат инструмента закончен.
        if (this.swapState == ST_SWAPPING_OUT) {
            this.swappedInventorySlot = -1;
            this.swapState = ST_IDLE;
            return;
        }

        if (!breaking) {
            restoreNow(player);
            return;
        }

        BlockState blockState = crystalAuraBreakState(client) != null
                ? crystalAuraBreakState(client)
                : client.level.getBlockState(((BlockHitResult) client.hitResult).getBlockPos());

        switch (this.swapState) {
            case ST_IDLE -> {
                int bestSlot = findBestToolSlot(player, blockState);
                if (bestSlot == -1) return;
                if (this.originalHotbarSlot == -1) {
                    this.originalHotbarSlot = player.getInventory().getSelectedSlot();
                }
                if (bestSlot < HOTBAR_SIZE) {
                    selectHotbarTool(player, bestSlot);
                    return;
                }
                if (!this.useInventory.getValue()) return;
                // Лучший инструмент в инвентаре — берём прямо в ведущую руку. Глобальный
                // InventorySwap свапает его в текущий слот руки, после усадки выбираем слот —
                // инструмент в руке, затем ломка. Свап и ломка не пересекаются по тикам.
                InventorySwap.moveToHotbar(bestSlot, this.originalHotbarSlot);
                this.swappedInventorySlot = bestSlot;
                this.swapState = ST_SWAPPING_IN;
                gateDestroy();
            }
            case ST_SWAPPING_IN -> {
                if (InventorySwap.isBusy()) {
                    gateDestroy();
                    return;
                }
                player.getInventory().setSelectedSlot(this.originalHotbarSlot);
                this.swapState = ST_READY;
            }
            case ST_READY -> {
                int bestSlot = findBestToolSlot(player, blockState);
                if (bestSlot == this.swappedInventorySlot
                        || bestSlot == this.originalHotbarSlot) {
                    return;
                }
                if (bestSlot == -1 || bestSlot >= HOTBAR_SIZE && !this.useInventory.getValue()) {
                    return;
                }
                // Лучший инструмент сменился (другой блок) — возвращаем текущий и пересвапаем.
                InventorySwap.moveToHotbar(this.swappedInventorySlot, this.originalHotbarSlot);
                this.swapState = ST_SWAPPING_OUT;
            }
            default -> {
            }
        }
    }

    /**
     * Синхронный вход для CrystalAura: вызывается прямо перед startDestroyBlock. Если
     * инструмент в инвентаре, только запускает свап и гейтит destroyDelay — ломка
     * начнётся через тик-два, когда инструмент уже в руке.
     */
    public boolean swapForBlock(BlockState state) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!isEnabled() || player == null || state == null) {
            return false;
        }
        int bestSlot = findBestToolSlot(player, state);
        if (bestSlot < 0) {
            return false;
        }
        if (this.originalHotbarSlot == -1) {
            this.originalHotbarSlot = player.getInventory().getSelectedSlot();
        }
        if (bestSlot < HOTBAR_SIZE) {
            selectHotbarTool(player, bestSlot);
            return true;
        }
        if (!this.useInventory.getValue()) {
            return false;
        }
        if (this.swapState == ST_READY && this.swappedInventorySlot == bestSlot) {
            return true;
        }
        if (!InventorySwap.isBusy()) {
            InventorySwap.moveToHotbar(bestSlot, this.originalHotbarSlot);
            this.swappedInventorySlot = bestSlot;
            this.swapState = ST_SWAPPING_IN;
        }
        gateDestroy();
        return this.swapState == ST_READY;
    }

    private boolean isBreakingBlock(Minecraft client) {
        return client.options.keyAttack.isDown()
                && client.hitResult != null
                && client.hitResult.getType() == HitResult.Type.BLOCK;
    }

    /**
     * Javelin AutoTool.isMousePressed: пока CrystalAura делает подкоп (BREAK),
     * держим лучший инструмент для разбиваемого блока, а не тот, что в руке.
     */
    private BlockState crystalAuraBreakState(Minecraft client) {
        CrystalAuraFeature aura = FeatureManager.INSTANCE.getFeature(CrystalAuraFeature.class);
        if (aura == null || !aura.isEnabled() || !aura.getAutoKrot().getValue() || client.level == null) {
            return null;
        }
        CrystalAuraFeature.PlaceData data = aura.getLastData();
        if (data == null || data.blockPos() == null
                || data.placeResult() != CrystalAuraFeature.PlaceResult.BREAK) {
            return null;
        }
        return client.level.getBlockState(data.blockPos());
    }

    private int findBestToolSlot(LocalPlayer player, BlockState state) {
        int limit = useInventory.getValue() ? INVENTORY_SIZE : HOTBAR_SIZE;
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void selectHotbarTool(LocalPlayer player, int slot) {
        Minecraft client = Minecraft.getInstance();
        if (this.mode.is("Normal")) {
            restoreSilentServerSlot(player);
            player.getInventory().setSelectedSlot(slot);
            // Форсим SetCarriedItem сразу: сервер узнаёт о смене слота до ломки.
            if (client.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                acc.invokerEnsureHasSentCarriedItem();
            }
            return;
        }
        if (slot == player.getInventory().getSelectedSlot()) {
            restoreSilentServerSlot(player);
            return;
        }
        if (this.silentServerSlot != slot) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            this.silentServerSlot = slot;
        }
    }

    /**
     * Пока нечего ломать (или фича отключается) — приводим руку в исходное состояние.
     * Инвентарный инструмент возвращается обратно через глобальный InventorySwap.
     */
    private void restoreNow(LocalPlayer player) {
        boolean hasSwap = this.swappedInventorySlot != -1 && this.originalHotbarSlot != -1;
        if (hasSwap) {
            if (InventorySwap.isBusy()) {
                return; // подождём усадки, поля не трогаем
            }
            if (this.swapState == ST_SWAPPING_OUT) {
                return; // уже возвращаемся, обратится в onTick
            }
            InventorySwap.moveToHotbar(this.swappedInventorySlot, this.originalHotbarSlot);
            this.swapState = ST_SWAPPING_OUT;
            return;
        }
        if (player != null) {
            restoreSilentServerSlot(player);
            if (this.originalHotbarSlot != -1) {
                player.getInventory().setSelectedSlot(this.originalHotbarSlot);
            }
        }
        this.originalHotbarSlot = -1;
        this.swapState = ST_IDLE;
    }

    /**
     * Пока свап инструмента в руку не сел, блокируем старт ломки: destroyDelay
     * декрементится в MultiPlayerGameMode.tick, CrystalAura проверяет его <= 1 и
     * ждёт. Как только инструмент в руке (ST_READY), гейт отпускается и ломка идёт
     * с правильным инструментом.
     */
    private void gateDestroy() {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode instanceof MultiPlayerGameModeAccessor acc) {
            acc.setDestroyDelay(2);
        }
    }

    private void restoreSilentServerSlot(LocalPlayer player) {
        if (this.silentServerSlot == -1) {
            return;
        }
        player.connection.send(new ServerboundSetCarriedItemPacket(
                player.getInventory().getSelectedSlot()
        ));
        this.silentServerSlot = -1;
    }
}