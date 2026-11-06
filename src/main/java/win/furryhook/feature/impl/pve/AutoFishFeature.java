package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.mixin.accessor.FishingHookAccessor;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;

import java.util.concurrent.atomic.AtomicReference;

public final class AutoFishFeature extends PveFeature {
    private static final int SAVE_DURABILITY = 10;
    private static final int INVENTORY_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;
    private static final long HOOK_APPEAR_TIMEOUT_TICKS = 40L;
    private static final long RECAST_DELAY_TICKS = 8L;
    private static final long BITE_LATCH_TIMEOUT_TICKS = 40L;
    private static final long SPLASH_MAX_AGE_NANOS = 2_000_000_000L;
    private static final double SPLASH_DISTANCE_SQUARED = 9.0D;

    public final BooleanSetting saveRod = register(new BooleanSetting("Save Rod", false));

    private final AtomicReference<SplashSignal> splashSignal = new AtomicReference<>();

    private FishingState state = FishingState.READY_TO_CAST;
    private long tick;
    private long stateSinceTick;
    private int observedHookId = -1;
    private boolean wasBiting;
    private boolean biteLatched;
    private long biteLatchedAtTick;
    private InteractionHand activeHand;
    private int swapSettleTicks;

    private int managedHotbarSlot = -1;
    private int managedInventorySlot = -1;
    private int restoreSelectedSlot = -1;
    private ItemStack displacedStack = ItemStack.EMPTY;

    public AutoFishFeature() {
        super(
                "AutoFish",
                "Casts and reels a fishing rod automatically",
                FeatureCategory.PLAYER,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE
        );
    }

    @Override
    protected void onPveEnable() {
        resetRuntime();
    }

    @Override
    protected void onPveDisable() {
        restoreManagedRod();
        resetRuntime();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        this.tick++;

        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            if (player != null && hasManagedRod()) {
                restoreManagedRod();
            }
            resetForMissingWorld();
            return;
        }
        if (client.gui.screen() != null) {
            return;
        }

        FishingHook hook = player.fishing;
        if (hook == null || hook.getPlayerOwner() != player || hook.isRemoved()) {
            handleHookAbsent(client, player);
            return;
        }
        handleHookPresent(client, player, hook);
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE) {
            return;
        }

        if (event.getPacket() instanceof ClientboundSoundPacket packet
                && isBobberSplash(packet.getSound())) {
            this.splashSignal.set(new SplashSignal(
                    packet.getX(),
                    packet.getY(),
                    packet.getZ(),
                    -1,
                    System.nanoTime()
            ));
            return;
        }

        if (event.getPacket() instanceof ClientboundSoundEntityPacket packet
                && isBobberSplash(packet.getSound())) {
            this.splashSignal.set(new SplashSignal(
                    0.0D,
                    0.0D,
                    0.0D,
                    packet.getId(),
                    System.nanoTime()
            ));
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        restoreManagedRod();
        resetRuntime();
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        resetRuntime();
    }

    private void handleHookAbsent(Minecraft client, LocalPlayer player) {
        clearObservedHook();

        if (this.state == FishingState.REEL_SENT
                || this.state == FishingState.WAITING_FOR_BITE) {
            transition(FishingState.RECAST_COOLDOWN);
            return;
        }
        if (this.state == FishingState.WAITING_FOR_HOOK) {
            if (ticksInState() >= HOOK_APPEAR_TIMEOUT_TICKS) {
                transition(FishingState.READY_TO_CAST);
            }
            return;
        }
        if (this.state == FishingState.RECAST_COOLDOWN) {
            if (ticksInState() >= RECAST_DELAY_TICKS) {
                transition(FishingState.READY_TO_CAST);
            }
            return;
        }

        InteractionHand hand = prepareRod(player);
        if (hand == null) {
            return;
        }

        this.splashSignal.set(null);
        useRod(client, player, hand);
        this.activeHand = hand;
        transition(FishingState.WAITING_FOR_HOOK);
    }

    private void handleHookPresent(Minecraft client, LocalPlayer player, FishingHook hook) {
        if (hook.getId() != this.observedHookId) {
            this.observedHookId = hook.getId();
            this.wasBiting = false;
            this.biteLatched = false;
            transition(FishingState.WAITING_FOR_BITE);
        } else if (this.state != FishingState.REEL_SENT
                && this.state != FishingState.WAITING_FOR_BITE) {
            transition(FishingState.WAITING_FOR_BITE);
        }

        boolean biting = ((FishingHookAccessor) hook).furryhook$isBiting();
        if (biting && !this.wasBiting) {
            latchBite();
        }
        if (consumeSplashNear(hook)) {
            latchBite();
        }
        this.wasBiting = biting;

        if (this.state == FishingState.REEL_SENT || !this.biteLatched) {
            return;
        }
        if (this.tick - this.biteLatchedAtTick > BITE_LATCH_TIMEOUT_TICKS) {
            this.biteLatched = false;
            return;
        }

        InteractionHand hand = prepareRod(player);
        if (hand == null) {
            return;
        }

        useRod(client, player, hand);
        this.activeHand = hand;
        this.biteLatched = false;
        transition(FishingState.REEL_SENT);
    }

    private InteractionHand prepareRod(LocalPlayer player) {
        if (this.swapSettleTicks > 0) {
            this.swapSettleTicks--;
            return null;
        }

        InteractionHand heldHand = findHeldRodHand(player);
        if (heldHand == null) {
            return null;
        }
        ItemStack held = player.getItemInHand(heldHand);
        if (!this.saveRod.getValue() || remainingDurability(held) > SAVE_DURABILITY) {
            return heldHand;
        }

        if (hasManagedRod()) {
            if (restoreManagedRod()) {
                this.swapSettleTicks = Math.max(this.swapSettleTicks, 1);
            }
            return null;
        }

        RodCandidate candidate = findBestUsableRod(player);
        if (candidate == null) {
            return null;
        }
        if (candidate.offhand()) {
            return InteractionHand.OFF_HAND;
        }
        return activateInventoryRod(player, candidate.inventoryIndex());
    }

    private InteractionHand findHeldRodHand(LocalPlayer player) {
        if (this.managedHotbarSlot >= 0
                && player.getInventory().getSelectedSlot() == this.managedHotbarSlot
                && isRod(player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        if (this.activeHand != null && isRod(player.getItemInHand(this.activeHand))) {
            return this.activeHand;
        }
        if (isRod(player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        if (isRod(player.getOffhandItem())) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private RodCandidate findBestUsableRod(LocalPlayer player) {
        RodCandidate best = null;
        int selectedSlot = player.getInventory().getSelectedSlot();

        for (int index = 0; index < INVENTORY_SIZE; index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (!isUsableReplacement(stack)) {
                continue;
            }
            int accessibility = index == selectedSlot ? 3 : index < HOTBAR_SIZE ? 2 : 1;
            RodCandidate candidate = new RodCandidate(
                    index,
                    false,
                    remainingDurability(stack),
                    unbreakingLevel(stack),
                    accessibility
            );
            if (isBetter(candidate, best)) {
                best = candidate;
            }
        }

        ItemStack offhand = player.getOffhandItem();
        if (isUsableReplacement(offhand)) {
            RodCandidate candidate = new RodCandidate(
                    -1,
                    true,
                    remainingDurability(offhand),
                    unbreakingLevel(offhand),
                    3
            );
            if (isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private InteractionHand activateInventoryRod(LocalPlayer player, int inventoryIndex) {
        int selectedSlot = player.getInventory().getSelectedSlot();
        if (inventoryIndex == selectedSlot) {
            return InteractionHand.MAIN_HAND;
        }
        if (!claim(AutomationResource.INVENTORY)) {
            return null;
        }

        try {
            if (!isUsableReplacement(player.getInventory().getItem(inventoryIndex))) {
                return null;
            }
            if (inventoryIndex < HOTBAR_SIZE) {
                this.restoreSelectedSlot = selectedSlot;
                this.managedHotbarSlot = inventoryIndex;
                player.getInventory().setSelectedSlot(inventoryIndex);
                return InteractionHand.MAIN_HAND;
            }
            if (player.containerMenu != player.inventoryMenu
                    || !player.inventoryMenu.getCarried().isEmpty()) {
                return null;
            }

            this.displacedStack = player.getInventory().getItem(selectedSlot).copy();
            Minecraft.getInstance().gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    inventoryIndex,
                    selectedSlot,
                    ContainerInput.SWAP,
                    player
            );
            this.managedInventorySlot = inventoryIndex;
            this.managedHotbarSlot = selectedSlot;
            this.swapSettleTicks = 1;
            return null;
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    private boolean restoreManagedRod() {
        if (!hasManagedRod()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null
                || !claim(AutomationResource.INVENTORY)) {
            return false;
        }

        try {
            if (this.managedInventorySlot >= 0) {
                if (player.containerMenu != player.inventoryMenu
                        || !player.inventoryMenu.getCarried().isEmpty()) {
                    return false;
                }

                ItemStack source = player.getInventory().getItem(this.managedInventorySlot);
                ItemStack managed = player.getInventory().getItem(this.managedHotbarSlot);
                if (ItemStack.matches(source, this.displacedStack) && isRod(managed)) {
                    client.gameMode.handleContainerInput(
                            player.inventoryMenu.containerId,
                            this.managedInventorySlot,
                            this.managedHotbarSlot,
                            ContainerInput.SWAP,
                            player
                    );
                    this.swapSettleTicks = 1;
                }
            }

            if (this.restoreSelectedSlot >= 0
                    && player.getInventory().getSelectedSlot() == this.managedHotbarSlot) {
                player.getInventory().setSelectedSlot(this.restoreSelectedSlot);
            }
            clearManagedRod();
            return true;
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    private void useRod(Minecraft client, LocalPlayer player, InteractionHand hand) {
        if (!isRod(player.getItemInHand(hand))) {
            return;
        }
        InteractionResult result = client.gameMode.useItem(player, hand);
        if (result.consumesAction()) {
            player.swing(hand);
        }
    }

    private boolean consumeSplashNear(FishingHook hook) {
        SplashSignal signal = this.splashSignal.getAndSet(null);
        if (signal == null || System.nanoTime() - signal.receivedAtNanos() > SPLASH_MAX_AGE_NANOS) {
            return false;
        }
        if (signal.entityId() >= 0) {
            return signal.entityId() == hook.getId();
        }
        return hook.distanceToSqr(signal.x(), signal.y(), signal.z()) <= SPLASH_DISTANCE_SQUARED;
    }

    private void latchBite() {
        this.biteLatched = true;
        this.biteLatchedAtTick = this.tick;
    }

    private void transition(FishingState next) {
        if (this.state == next) {
            return;
        }
        this.state = next;
        this.stateSinceTick = this.tick;
    }

    private long ticksInState() {
        return Math.max(0L, this.tick - this.stateSinceTick);
    }

    private void clearObservedHook() {
        this.observedHookId = -1;
        this.wasBiting = false;
        this.biteLatched = false;
    }

    private void resetForMissingWorld() {
        this.splashSignal.set(null);
        this.state = FishingState.READY_TO_CAST;
        this.stateSinceTick = this.tick;
        this.activeHand = null;
        this.swapSettleTicks = 0;
        clearObservedHook();
        clearManagedRod();
    }

    private void resetRuntime() {
        this.tick = 0L;
        this.state = FishingState.READY_TO_CAST;
        this.stateSinceTick = 0L;
        this.activeHand = null;
        this.swapSettleTicks = 0;
        this.splashSignal.set(null);
        clearObservedHook();
        clearManagedRod();
    }

    private boolean hasManagedRod() {
        return this.managedHotbarSlot >= 0;
    }

    private void clearManagedRod() {
        this.managedHotbarSlot = -1;
        this.managedInventorySlot = -1;
        this.restoreSelectedSlot = -1;
        this.displacedStack = ItemStack.EMPTY;
    }

    private static boolean isBobberSplash(Holder<SoundEvent> sound) {
        return sound != null && sound.value() == SoundEvents.FISHING_BOBBER_SPLASH;
    }

    private static boolean isRod(ItemStack stack) {
        return stack != null && stack.is(Items.FISHING_ROD);
    }

    private static boolean isUsableReplacement(ItemStack stack) {
        return isRod(stack) && isUsableRodDurability(remainingDurability(stack));
    }

    static boolean isUsableRodDurability(int remainingDurability) {
        return remainingDurability > SAVE_DURABILITY;
    }

    private static int remainingDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private static int unbreakingLevel(ItemStack stack) {
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING)) {
                return stack.getEnchantments().getLevel(enchantment);
            }
        }
        return 0;
    }

    static int compareRodQuality(int remainingA, int unbreakingA, int remainingB, int unbreakingB) {
        int durability = Integer.compare(remainingA, remainingB);
        return durability != 0 ? durability : Integer.compare(unbreakingA, unbreakingB);
    }

    private static boolean isBetter(RodCandidate candidate, RodCandidate currentBest) {
        if (currentBest == null) {
            return true;
        }
        int quality = compareRodQuality(
                candidate.remainingDurability(),
                candidate.unbreakingLevel(),
                currentBest.remainingDurability(),
                currentBest.unbreakingLevel()
        );
        if (quality != 0) {
            return quality > 0;
        }
        if (candidate.accessibility() != currentBest.accessibility()) {
            return candidate.accessibility() > currentBest.accessibility();
        }
        return candidate.inventoryIndex() < currentBest.inventoryIndex();
    }

    private enum FishingState {
        READY_TO_CAST,
        WAITING_FOR_HOOK,
        WAITING_FOR_BITE,
        REEL_SENT,
        RECAST_COOLDOWN
    }

    private record SplashSignal(
            double x,
            double y,
            double z,
            int entityId,
            long receivedAtNanos
    ) {
    }

    private record RodCandidate(
            int inventoryIndex,
            boolean offhand,
            int remainingDurability,
            int unbreakingLevel,
            int accessibility
    ) {
    }
}
