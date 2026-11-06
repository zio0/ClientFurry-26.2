package win.furryhook.utils.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;

public final class InventorySwap implements MinecraftContext {
    public static final InventorySwap INSTANCE = new InventorySwap();

    private static final int HOTBAR_START = InventoryMenu.USE_ROW_SLOT_START;
    private static final int HOTBAR_END = InventoryMenu.USE_ROW_SLOT_END;
    private static final int OFFHAND_BUTTON = 40;

    private enum State {
        IDLE,

        MOVING,
        
        HOLDING_BEFORE_USE,

        USING,

        WAITING_FOR_USE,
        
        HOLDING_AFTER_USE,

        RETURNING,

        SETTLING
    }

    private State state = State.IDLE;
    private int sourceSlot = -1;
    private int hotbarButton = OFFHAND_BUTTON;
    private boolean useAndReturn;
    private boolean waitForUseCompletion;
    private boolean immediateReturn;
    private boolean directUse;
    private ContainerInput clickInput = ContainerInput.SWAP;
    private boolean syntheticUseHeld;
    private int useWaitTicks;
    private static final int MAX_USE_WAIT_TICKS = 200;
    
    // Delay ticks for hold before and after use
    private int holdBeforeTicks = 0;
    private int holdAfterTicks = 0;
    private int currentHoldTicks = 0;

    private InventorySwap() {
    }

    public static void equip(int containerSlot) {
        LocalPlayer player = INSTANCE.player();
        if (player == null || INSTANCE.state != State.IDLE
                || DropAllInventoryController.blocksInventoryOperations()
                || player.containerMenu != player.inventoryMenu) {
            return;
        }
        if (containerSlot >= HOTBAR_START && containerSlot < HOTBAR_END) {
            mc.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    containerSlot,
                    OFFHAND_BUTTON,
                    ContainerInput.SWAP,
                    player
            );
            return;
        }
        INSTANCE.begin(
                containerSlot,
                OFFHAND_BUTTON,
                false,
                false,
                false,
                false,
                ContainerInput.SWAP
        );
    }

    public static void moveToHotbar(int containerSlot, int hotbarSlot) {
        if (INSTANCE.player() == null || INSTANCE.state != State.IDLE
                || DropAllInventoryController.blocksInventoryOperations()
                || hotbarSlot < 0 || hotbarSlot > 8) {
            return;
        }
        INSTANCE.begin(
                containerSlot,
                hotbarSlot,
                false,
                false,
                false,
                false,
                ContainerInput.SWAP
        );
    }

    public static void useFromSlot(int containerSlot) {
        useFromSlot(containerSlot, false);
    }

    public static void useFromSlot(int containerSlot, boolean waitForUseCompletion) {
        useFromSlot(containerSlot, waitForUseCompletion, false);
    }

    public static void useFromSlot(int containerSlot,
                                   boolean waitForUseCompletion,
                                   boolean immediateReturn) {
        useFromSlot(containerSlot, waitForUseCompletion, immediateReturn, 0, 0);
    }

    public static void useFromSlot(int containerSlot,
                                   boolean waitForUseCompletion,
                                   boolean immediateReturn,
                                   int holdBeforeTicks,
                                   int holdAfterTicks) {
        LocalPlayer player = INSTANCE.player();
        if (player == null || INSTANCE.state != State.IDLE
                || DropAllInventoryController.blocksInventoryOperations()
                || player.containerMenu != player.inventoryMenu) {
            return;
        }
        INSTANCE.beginWithDelay(
                containerSlot,
                player.getInventory().getSelectedSlot(),
                true,
                waitForUseCompletion,
                immediateReturn,
                false,
                ContainerInput.SWAP,
                holdBeforeTicks,
                holdAfterTicks
        );
    }

    public static void useSelected(boolean waitForUseCompletion) {
        LocalPlayer player = INSTANCE.player();
        if (player == null || INSTANCE.state != State.IDLE
                || DropAllInventoryController.blocksInventoryOperations()
                || player.containerMenu != player.inventoryMenu) {
            return;
        }
        INSTANCE.begin(
                -1,
                player.getInventory().getSelectedSlot(),
                true,
                waitForUseCompletion,
                false,
                true,
                ContainerInput.SWAP
        );
    }

    public static boolean dropStack(int containerSlot) {
        LocalPlayer player = INSTANCE.player();
        if (player == null
                || INSTANCE.state != State.IDLE
                || DropAllInventoryController.blocksInventoryOperations()
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.isValidSlotIndex(containerSlot)
                || player.inventoryMenu.getSlot(containerSlot).getItem().isEmpty()) {
            return false;
        }
        INSTANCE.begin(
                containerSlot,
                1,
                false,
                false,
                false,
                false,
                ContainerInput.THROW
        );
        return true;
    }

    public static boolean isBusy() {
        return INSTANCE.state != State.IDLE;
    }

    public static boolean shouldStopMovement() {
        return INSTANCE.state != State.IDLE;
    }

    public static void abort() {
        INSTANCE.finish();
    }

    private void begin(int containerSlot,
                       int hotbarButton,
                       boolean useAndReturn,
                       boolean waitForUseCompletion,
                       boolean immediateReturn,
                       boolean directUse,
                       ContainerInput clickInput) {
        beginWithDelay(containerSlot, hotbarButton, useAndReturn, waitForUseCompletion, 
                      immediateReturn, directUse, clickInput, 0, 0);
    }

    private void beginWithDelay(int containerSlot,
                                int hotbarButton,
                                boolean useAndReturn,
                                boolean waitForUseCompletion,
                                boolean immediateReturn,
                                boolean directUse,
                                ContainerInput clickInput,
                                int holdBeforeTicks,
                                int holdAfterTicks) {
        this.sourceSlot = containerSlot;
        this.hotbarButton = hotbarButton;
        this.useAndReturn = useAndReturn;
        this.waitForUseCompletion = waitForUseCompletion;
        this.immediateReturn = immediateReturn;
        this.directUse = directUse;
        this.clickInput = clickInput == null ? ContainerInput.SWAP : clickInput;
        this.syntheticUseHeld = false;
        this.useWaitTicks = 0;
        this.holdBeforeTicks = Math.max(0, holdBeforeTicks);
        this.holdAfterTicks = Math.max(0, holdAfterTicks);
        this.currentHoldTicks = 0;

        this.state = State.MOVING;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (this.state == State.IDLE) {
            return;
        }

        LocalPlayer player = player();
        if (player == null
                || !this.directUse && !player.inventoryMenu.isValidSlotIndex(this.sourceSlot)

                || player.containerMenu != player.inventoryMenu) {
            finish();
            return;
        }

        switch (this.state) {
            case MOVING -> {
                if (!this.directUse) {
                    if (player.inventoryMenu.getSlot(this.sourceSlot).getItem().isEmpty()) {
                        finish();
                        return;
                    }
                    click(player);
                }
                
                // Check if we need to hold before use
                if (this.holdBeforeTicks > 0 && this.useAndReturn) {
                    this.currentHoldTicks = this.holdBeforeTicks;
                    this.state = State.HOLDING_BEFORE_USE;
                } else {
                    this.state = this.useAndReturn ? State.USING : State.SETTLING;
                }
            }
            case HOLDING_BEFORE_USE -> {
                this.currentHoldTicks--;
                if (this.currentHoldTicks <= 0) {
                    this.state = State.USING;
                }
            }
            case USING -> {
                int selected = player.getInventory().getSelectedSlot();
                if (selected != this.hotbarButton) {
                    player.getInventory().setSelectedSlot(this.hotbarButton);
                }
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
                if (selected != this.hotbarButton) {
                    player.getInventory().setSelectedSlot(selected);
                }
                if (this.waitForUseCompletion && player.isUsingItem()) {
                    mc.options.keyUse.setDown(true);
                    this.syntheticUseHeld = true;
                    this.state = State.WAITING_FOR_USE;
                } else if (this.immediateReturn && !this.directUse) {
                    click(player);
                    this.state = State.SETTLING;
                } else if (this.holdAfterTicks > 0) {
                    this.currentHoldTicks = this.holdAfterTicks;
                    this.state = State.HOLDING_AFTER_USE;
                } else {
                    this.state = State.RETURNING;
                }
            }
            case WAITING_FOR_USE -> {
                this.useWaitTicks++;
                if (!player.isUsingItem() || this.useWaitTicks >= MAX_USE_WAIT_TICKS) {
                    releaseSyntheticUse();
                    if (this.holdAfterTicks > 0) {
                        this.currentHoldTicks = this.holdAfterTicks;
                        this.state = State.HOLDING_AFTER_USE;
                    } else {
                        this.state = State.RETURNING;
                    }
                }
            }
            case HOLDING_AFTER_USE -> {
                this.currentHoldTicks--;
                if (this.currentHoldTicks <= 0) {
                    this.state = State.RETURNING;
                }
            }
            case RETURNING -> {
                if (!this.directUse) {
                    click(player);
                }
                this.state = State.SETTLING;
            }
            case SETTLING -> finish();
            default -> finish();
        }
    }

    private void click(LocalPlayer player) {
        mc.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                this.sourceSlot,
                this.hotbarButton,
                this.clickInput,
                player
        );
    }

    private void finish() {
        releaseSyntheticUse();
        this.state = State.IDLE;
        this.sourceSlot = -1;
        this.hotbarButton = OFFHAND_BUTTON;
        this.useAndReturn = false;
        this.waitForUseCompletion = false;
        this.immediateReturn = false;
        this.directUse = false;
        this.clickInput = ContainerInput.SWAP;
        this.useWaitTicks = 0;
        this.holdBeforeTicks = 0;
        this.holdAfterTicks = 0;
        this.currentHoldTicks = 0;
    }

    private void releaseSyntheticUse() {
        if (this.syntheticUseHeld) {
            mc.options.keyUse.setDown(false);
            this.syntheticUseHeld = false;
        }
    }
}
