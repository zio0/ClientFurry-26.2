package win.furryhook.utils.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * Firing a firework entirely by packets — the client slot and HUD are never touched.
 *
 * <p>The rocket is pulled into the main hand only server-side: a raw
 * {@link ServerboundSetCarriedItemPacket} moves the carried slot, the use lands a tick later as
 * {@link ServerboundUseItemPacket}, and a second carried-item packet then syncs the server back to
 * whatever the player is actually holding. The hotbar selection, the HUD and the client-side
 * carried-item pipeline are left alone, so nothing desyncs and nothing needs restoring visually.
 *
 * <p>Each leg lands on its own tick (switch → gap → use → gap → switch-back). Bursting the switch
 * and the use in a single tick makes the rocket "not launch on the first try" — the server has not
 * applied the carried-slot change before the use arrives. Stepping keeps the first use reliable
 * without looking like carried-item churn to Grim.
 *
 * <p>No off-hand is involved: a rocket already in the off-hand is used from there, but the
 * mechanic never lifts an inventory rock into the left hand.
 *
 * <p>{@link #tick(LocalPlayer)} must be called once per game tick while a use may be in flight.
 * {@link #suppressesSwing(Packet)} keeps the server's echo swing from playing a rocket animation
 * on the real hand item; call it from a packet-receive listener.
 */
public final class FireworkUse {
    /** How long after a rocket a self-swing broadcast is treated as belonging to it. */
    private static final long SWING_WINDOW_MS = 400L;

    /** Ticks between the slot switch and the use, and between the use and the switch-back. */
    private static final int GAP_BEFORE_USE = 1;
    private static final int GAP_AFTER_USE = 1;

    private static long lastUseMs = Long.MIN_VALUE;

    private static int rocketSlot = -1;
    private static int swapBackSlot = -1;
    /** The carried slot the server was last told about; used for the catch-up switch-back. */
    private static int silentCarriedSlot = -1;
    private static int phase = 0;
    private static int phaseTicks = 0;

    private FireworkUse() {
    }

    /**
     * Fires one rocket if there is a firework to fire: moves the carried slot server-side and
     * queues the stepped use. The client slot and HUD are never touched.
     *
     * @param preferOffhand use the off-hand copy first when there is one
     * @return true when a rocket was actually used
     */
    public static boolean use(LocalPlayer player, boolean preferOffhand) {
        if (player == null || player.connection == null) {
            return false;
        }
        // Vanilla firework cooldown (~1.25s) is deliberately not checked: it would throttle a
        // rapid binned spam down to a single rocket per cooldown. Without it each press fires at
        // once, back-to-back.

        boolean offhandRocket = player.getOffhandItem().is(Items.FIREWORK_ROCKET);
        boolean mainRocket = player.getMainHandItem().is(Items.FIREWORK_ROCKET);

        // Rocket already held: no slot dance at all, use straight away.
        if (offhandRocket && (preferOffhand || !mainRocket)) {
            sendUse(player, InteractionHand.OFF_HAND);
            return true;
        }
        if (mainRocket) {
            sendUse(player, InteractionHand.MAIN_HAND);
            return true;
        }

        // The mechanic never pulls a rocket into the off-hand; only the hotbar is reachable
        // by the carried-slot packets. Anything deeper in the inventory is out of reach.
        int slot = findHotbarRocket(player);
        if (slot < 0) {
            return false;
        }

        // A prior use that is still winding down is abandoned so the new rocket starts at once.
        // No restore-then-re-switch: the SetCarriedItem below simply overwrites the server state,
        // and the stale chain's own restore can no longer fire because its fields are overwritten.
        rocketSlot = slot;
        swapBackSlot = player.getInventory().getSelectedSlot();

        // Ракета уже в текущем слоте — юзаем сразу, пляски со слотом не нужны.
        if (swapBackSlot == slot) {
            sendUse(player, InteractionHand.MAIN_HAND);
            return true;
        }

        // Только серверный свитч: клиентский слот и HUD не трогаем, чтобы никакой десинх
        // «HUD = меч, сервер = фейерверк» между пакетами не накапливался.
        if (silentCarriedSlot != slot) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            silentCarriedSlot = slot;
        }

        phase = 2;
        phaseTicks = GAP_BEFORE_USE;
        return true;
    }

    /**
     * Advances the stepped use across ticks. Call once per game tick. Does nothing if no use is
     * queued.
     */
    public static void tick(LocalPlayer player) {
        if (player == null || player.connection == null) {
            reset();
            return;
        }
        if (phase == 0) {
            return;
        }

        switch (phase) {
            case 2 -> {
                if (phaseTicks > 0) {
                    phaseTicks--;
                    return;
                }
                // Пользователь сам сменил слот за паузу — ракеты в руке больше нет, юз отменяем
                // и сразу возвращаем серверный слот к тому, что сейчас выбрано у игрока.
                if (player.getInventory().getSelectedSlot() != swapBackSlot) {
                    reset();
                    return;
                }
                sendUse(player, InteractionHand.MAIN_HAND);
                phase = 3;
                phaseTicks = GAP_AFTER_USE;
            }
            case 3 -> {
                if (phaseTicks > 0) {
                    phaseTicks--;
                    return;
                }
                // Возврат: сервер приводим к текущему клиентскому слоту, чтобы после ракеты
                // не оставался «сервер держит фейерверк» — иначе следующий ручной юз/ставка
                // кристалла улетит с несовпадением руки и слота (кик на Гриме).
                restoreSilentCarried();
                rocketSlot = -1;
                swapBackSlot = -1;
                phase = 0;
                phaseTicks = 0;
            }
            default -> reset();
        }
    }

    /**
     * True for the self-swing the server sends back after one of our rockets — the packet that
     * would otherwise play a firework animation on whatever is really in hand. Call this from a
     * packet-receive listener and drop the packet when it answers true.
     */
    public static boolean suppressesSwing(Packet<?> packet) {
        if (!(packet instanceof ClientboundAnimatePacket animate)) {
            return false;
        }
        if (System.currentTimeMillis() - lastUseMs > SWING_WINDOW_MS) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || animate.getId() != player.getId()) {
            return false;
        }
        int action = animate.getAction();
        return action == ClientboundAnimatePacket.SWING_MAIN_HAND
                || action == ClientboundAnimatePacket.SWING_OFF_HAND;
    }

    public static void reset() {
        // Любой обрыв цепочки (выключение фичи, смерть, смена измерения, экран) обязан вернуть
        // серверный слот, иначе сервер навсегда «держит ракету» и ручной юз кристалла потом
        // улетает с несоответствием руки/слота — тот самый «иногда кикает» на Гриме.
        restoreSilentCarried();
        rocketSlot = -1;
        swapBackSlot = -1;
        phase = 0;
        phaseTicks = 0;
    }

    /**
     * Синхронизирует серверный carried-слот с текущим клиентским выбором, если мы его уводили.
     * Безопасен для вызова из любого места: ничего не шлёт, когда слот и так совпадает.
     */
    private static void restoreSilentCarried() {
        if (silentCarriedSlot == -1) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (selected != silentCarriedSlot) {
            player.connection.send(new ServerboundSetCarriedItemPacket(selected));
        }
        silentCarriedSlot = -1;
    }

    private static int findHotbarRocket(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Sequence zero: the field only acknowledges block changes the client has predicted, and this
     * path predicts none. The client's own counter is not reachable — the prediction handler is
     * package-private on ClientLevel — and an item use is accepted regardless of what it carries.
     */
    private static void sendUse(LocalPlayer player, InteractionHand hand) {
        player.connection.send(new ServerboundUseItemPacket(hand, 0, player.getYRot(), player.getXRot()));
        lastUseMs = System.currentTimeMillis();
    }
}