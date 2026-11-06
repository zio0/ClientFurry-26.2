package win.furryhook.feature.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.player.Input;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketSendEvent;
import win.furryhook.event.events.screen.ScreenCloseEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GuiMove / InventoryMove ported from main.syntax.modules.impl.movement.GuiMove.
 * Supports DEFAULT, BYPASS, and LEGIT modes for matrix/grim anti-cheat bypasses.
 */
public final class InventoryMoveFeature extends Feature implements MinecraftContext {

    public enum Mode {
        DEFAULT("Default"),
        BYPASS("Bypass"),
        LEGIT("Legit");

        private final String name;

        Mode(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public final ModeSetting mode = register(new ModeSetting(
            "Mode",
            Mode.DEFAULT.getName(),
            Mode.DEFAULT.getName(),
            Mode.BYPASS.getName(),
            Mode.LEGIT.getName()
    ));

    private final List<Packet<?>> delayedPackets = new CopyOnWriteArrayList<>();
    private boolean processingPackets = false;
    private boolean movedInGui = false;
    private boolean movementLocked = false;
    private int scriptStep = -1;
    private int stepTimer = 0;

    public InventoryMoveFeature() {
        super("GuiMove", "Walk around while your inventory is open with anti-cheat bypasses", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static InventoryMoveFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(InventoryMoveFeature.class);
    }

    public static Input screenInput() {
        InventoryMoveFeature feature = getEnabled();
        boolean clickGuiOpen = win.furryhook.menu.core.MenuOverlay.isOpen();
        if (mc.player == null) {
            return null;
        }
        if (!clickGuiOpen && (feature == null || !(feature.screen() instanceof InventoryScreen || feature.screen() instanceof CreativeModeInventoryScreen))) {
            return null;
        }
        return new Input(
                isKeyDown(mc.options.keyUp),
                isKeyDown(mc.options.keyDown),
                isKeyDown(mc.options.keyLeft),
                isKeyDown(mc.options.keyRight),
                isKeyDown(mc.options.keyJump),
                isKeyDown(mc.options.keyShift),
                isKeyDown(mc.options.keySprint)
        );
    }

    public static boolean shouldStopMovement() {
        InventoryMoveFeature feature = getEnabled();
        return feature != null && (feature.movementLocked || feature.processingPackets);
    }

    public static boolean isClickPipelineBusy() {
        InventoryMoveFeature feature = getEnabled();
        return feature != null && (feature.processingPackets || !feature.delayedPackets.isEmpty());
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = player();
        if (player == null) {
            cleanup();
            return;
        }

        if (!(screen() instanceof InventoryScreen || screen() instanceof CreativeModeInventoryScreen || win.furryhook.menu.core.MenuOverlay.isOpen())) {
            if (!processingPackets && delayedPackets.isEmpty()) {
                movedInGui = false;
            }
            if (processingPackets) {
                tickScript();
            }
            return;
        }

        movedInGui |= movementKeysDown() && !delayedPackets.isEmpty();

        if (processingPackets) {
            tickScript();
        }
    }

    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = player();
        if (player == null || event.getPhase() != PacketSendEvent.Phase.PRE) {
            return;
        }

        if (this.mode.is(Mode.DEFAULT.getName())) {
            return;
        }

        boolean moving = movedInGui || movementKeysDown();
        movedInGui |= moving && !delayedPackets.isEmpty();

        Packet<?> packet = event.getPacket();

        if (packet instanceof ServerboundContainerClickPacket clickPacket
                && screen() instanceof InventoryScreen
                && moving
                && shouldAllowMovement()) {
            delayedPackets.add(clickPacket);
            event.cancel();
        } else if (packet instanceof ServerboundContainerClosePacket closePacket
                && closePacket.getContainerId() == 0
                && moving
                && !processingPackets) {
            if (delayedPackets.isEmpty()) {
                event.cancel();
            } else {
                delayedPackets.add(closePacket);
                event.cancel();
                processDelayedPackets();
            }
        }

        if (processingPackets && packet instanceof ServerboundPlayerInputPacket) {
            event.cancel();
            if (player.connection != null) {
                player.connection.send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, false, false)));
            }
        }

        if (!delayedPackets.isEmpty() && processingPackets) {
            if (packet instanceof ServerboundSwingPacket
                    || packet instanceof ServerboundInteractPacket
                    || packet instanceof ServerboundUseItemPacket
                    || packet instanceof ServerboundUseItemOnPacket) {
                event.cancel();
            }
        }
    }

    private void processDelayedPackets() {
        processingPackets = true;
        scriptStep = 0;
        stepTimer = 0;
    }

    private void tickScript() {
        if (!processingPackets || scriptStep < 0) {
            return;
        }

        LocalPlayer player = player();
        if (player == null || player.connection == null) {
            cleanup();
            return;
        }

        boolean isBypass = this.mode.is(Mode.BYPASS.getName());

        if (isBypass) {
            switch (scriptStep) {
                case 0 -> { // Lock movement
                    movementLocked = true;
                    scriptStep++;
                }
                case 1 -> { // Send all delayed click packets
                    for (Packet<?> p : delayedPackets) {
                        player.connection.send(p);
                    }
                    delayedPackets.clear();
                    processingPackets = false;
                    movedInGui = false;
                    scriptStep++;
                }
                case 2 -> { // Send close packet & unlock movement
                    for (Packet<?> p : delayedPackets) {
                        if (p instanceof ServerboundContainerClosePacket) {
                            player.connection.send(p);
                        }
                    }
                    movementLocked = false;
                    scriptStep = -1;
                }
            }
        } else {
            // LEGIT Mode (Matrix / Grim delayed execution sequence)
            switch (scriptStep) {
                case 0 -> { // Lock movement
                    movementLocked = true;
                    scriptStep++;
                    stepTimer = 0;
                }
                case 1, 2 -> { // Wait 2 ticks
                    stepTimer++;
                    if (stepTimer >= 2) {
                        scriptStep++;
                        stepTimer = 0;
                    }
                }
                case 3 -> { // Send container click packets (except close packet)
                    for (Packet<?> p : delayedPackets) {
                        if (!(p instanceof ServerboundContainerClosePacket)) {
                            player.connection.send(p);
                        }
                    }
                    scriptStep++;
                    stepTimer = 0;
                }
                case 4, 5 -> { // Wait 2 ticks
                    stepTimer++;
                    if (stepTimer >= 2) {
                        scriptStep++;
                        stepTimer = 0;
                    }
                }
                case 6 -> { // Send close packet
                    for (Packet<?> p : delayedPackets) {
                        if (p instanceof ServerboundContainerClosePacket) {
                            player.connection.send(p);
                        }
                    }
                    delayedPackets.clear();
                    scriptStep++;
                    stepTimer = 0;
                }
                case 7 -> { // Unlock movement
                    movementLocked = false;
                    processingPackets = false;
                    movedInGui = false;
                    scriptStep = -1;
                }
            }
        }
    }

    private boolean movementKeysDown() {
        if (mc == null || mc.getWindow() == null || mc.options == null) {
            return false;
        }
        boolean inventory = screen() instanceof InventoryScreen || screen() instanceof CreativeModeInventoryScreen;

        KeyMapping[] keys = {mc.options.keyUp, mc.options.keyDown, mc.options.keyRight, mc.options.keyLeft, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint};

        for (KeyMapping binding : keys) {
            if (inventory && (binding == mc.options.keyShift || (binding == mc.options.keySprint && !mc.options.keyUp.equals(mc.options.keySprint)))) {
                continue;
            }
            if (isKeyDown(binding)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldAllowMovement() {
        LocalPlayer player = player();
        return player != null && player.containerMenu != null && player.containerMenu.slots.size() >= 27;
    }

    private void cleanup() {
        delayedPackets.clear();
        processingPackets = false;
        movedInGui = false;
        movementLocked = false;
        scriptStep = -1;
        stepTimer = 0;
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        cleanup();
    }

    @EventTarget
    public void onScreenClose(ScreenCloseEvent event) {
        if (!(event.getScreen() instanceof InventoryScreen)) {
            return;
        }
        KeyMapping.releaseAll();
    }

    @Override
    protected void onDisable() {
        LocalPlayer player = player();
        if (player != null && player.connection != null) {
            for (Packet<?> p : delayedPackets) {
                player.connection.send(p);
            }
        }
        cleanup();
    }

    private static boolean isKeyDown(KeyMapping mapping) {
        InputConstants.Key key = ((win.furryhook.mixin.accessor.KeyMappingAccessor) (Object) mapping).getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }
}
