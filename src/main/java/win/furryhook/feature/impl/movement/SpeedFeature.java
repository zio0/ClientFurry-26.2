package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.event.events.packet.PacketSendEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * Speed — визуальное ускорение до 100 bps, сервер видит игрока неподвижным.
 *
 * <p><b>Visual</b> mode: клиент едет вперёд с задаваемой скоростью, все исходящие
 * {@link ServerboundMovePlayerPacket} переписываются на замороженные server-side координаты.
 * Другие игроки/сервер видят стоящего чувака, ты — летящего. При drop item (Q / dropAll)
 * перед ServerboundPlayerActionPacket отправляется одиночный position-пакет с текущей
 * визуальной позицией — предмет спавнится там, где ты добежал. После ресинхронизации
 * якорь двигается на новую позицию и разгон продолжается уже оттуда.
 *
 * <p><b>Legit</b> — auto-sprint, не флагает.
 * <br><b>Micro</b> — тонкий strafe-jitter в per-tick tolerance Grim'а.
 */
public final class SpeedFeature extends Feature {

    private static final String MODE_VISUAL = "Visual";
    private static final String MODE_LEGIT = "Legit";
    private static final String MODE_MICRO = "Micro";

    private static final double MIN_INPUT_SQ = 1.0E-4D;
    private static final double HORIZ_CAP = 0.62D;

    public final ModeSetting mode = register(new ModeSetting(
            "Mode", MODE_VISUAL, MODE_VISUAL, MODE_LEGIT, MODE_MICRO
    ));

    public final NumberSetting bps = register(new NumberSetting(
            "BPS", 40.0D, 5.0D, 100.0D, 1.0D, ""
    ).visibleWhen(() -> mode.is(MODE_VISUAL)));

    public final BooleanSetting syncOnDrop = register(new BooleanSetting(
            "Sync On Drop", true
    ).visibleWhen(() -> mode.is(MODE_VISUAL)));

    public final BooleanSetting syncOnDisable = register(new BooleanSetting(
            "Sync On Disable", true
    ).visibleWhen(() -> mode.is(MODE_VISUAL)));

    public final BooleanSetting killMomentumY = register(new BooleanSetting(
            "No Fall Damage", true
    ).visibleWhen(() -> mode.is(MODE_VISUAL)));

    public final NumberSetting microBoost = register(new NumberSetting(
            "Micro Boost", 0.010D, 0.005D, 0.020D, 0.005D, ""
    ).visibleWhen(() -> mode.is(MODE_MICRO)));

    public final BooleanSetting onlyWhenMoving = register(new BooleanSetting(
            "Only With Input", true
    ));

    public final BooleanSetting autoSprint = register(new BooleanSetting(
            "Auto Sprint", true
    ));

    // Visual state
    private Vec3 anchorPos;      // где сервер думает мы стоим
    private float anchorYRot;
    private float anchorXRot;
    private boolean flushing;    // защита от рекурсии при синке

    public SpeedFeature() {
        super("Speed", "Визуальный спид до 100 bps + drop-sync", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @Override
    protected void onEnable() {
        anchorPos = null;
    }

    @Override
    protected void onDisable() {
        if (mode.is(MODE_VISUAL) && syncOnDisable.getValue()) {
            flushToVisual();
        }
        anchorPos = null;
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isEnabled() || !mode.is(MODE_VISUAL)) return;
        // Сервер телепортнул — принять новую позицию как якорь.
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            anchorPos = null;
        }
    }

    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        if (!isEnabled() || !mode.is(MODE_VISUAL)) return;
        if (flushing) return;

        Packet<?> packet = event.getPacket();

        // Перед дропом — синк на визуальную позицию, чтобы предмет спавнился там.
        if (packet instanceof ServerboundPlayerActionPacket action && syncOnDrop.getValue()) {
            ServerboundPlayerActionPacket.Action a = action.getAction();
            if (a == ServerboundPlayerActionPacket.Action.DROP_ITEM
                    || a == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
                flushToVisual();
            }
            return;
        }

        if (!(packet instanceof ServerboundMovePlayerPacket move)) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        if (anchorPos == null) {
            anchorPos = player.position();
            anchorYRot = player.getYRot();
            anchorXRot = player.getXRot();
        }

        // Переписываем координаты на замороженный якорь, поворот пропускаем реальный.
        float yaw = move.getYRot(anchorYRot);
        float pitch = move.getXRot(anchorXRot);
        anchorYRot = yaw;
        anchorXRot = pitch;

        boolean onGround = move.isOnGround();
        boolean hc = move.horizontalCollision();

        event.setPacket(new ServerboundMovePlayerPacket.PosRot(
                anchorPos.x, anchorPos.y, anchorPos.z, yaw, pitch, onGround, hc
        ));
    }

    private void flushToVisual() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        if (anchorPos == null) return;

        Vec3 visual = player.position();
        // Если разрыв ничтожен — не тратить пакет.
        if (visual.distanceToSqr(anchorPos) < 1.0E-4) {
            return;
        }
        flushing = true;
        try {
            player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                    visual.x, visual.y, visual.z,
                    player.getYRot(), player.getXRot(),
                    player.onGround(), player.horizontalCollision
            ));
        } finally {
            flushing = false;
        }
        anchorPos = visual;
        anchorYRot = player.getYRot();
        anchorXRot = player.getXRot();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null) return;

        if (mode.is(MODE_VISUAL)) {
            visualTick(player);
            return;
        }

        if (player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isSpectator()
                || player.isInWater() || player.isInLava()
                || player.onClimbable()) {
            return;
        }

        Vec2 input = player.input.getMoveVector();
        boolean hasInput = input.lengthSquared() >= MIN_INPUT_SQ;
        if (onlyWhenMoving.getValue() && !hasInput) return;

        if (autoSprint.getValue() && hasInput && input.y > 0.0F && !player.isSprinting()) {
            player.setSprinting(true);
        }

        if (!mode.is(MODE_MICRO)) return;

        Vec3 motion = player.getDeltaMovement();
        Vec3 forward = forwardVector(player, input);
        double push = microBoost.getValue();
        double newX = clamp(motion.x + forward.x * push, HORIZ_CAP);
        double newZ = clamp(motion.z + forward.z * push, HORIZ_CAP);
        player.setDeltaMovement(newX, motion.y, newZ);
    }

    private void visualTick(LocalPlayer player) {
        if (player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isSpectator()) {
            return;
        }

        Vec2 input = player.input.getMoveVector();
        boolean hasInput = input.lengthSquared() >= MIN_INPUT_SQ;

        if (autoSprint.getValue() && hasInput && input.y > 0.0F && !player.isSprinting()) {
            player.setSprinting(true);
        }

        if (onlyWhenMoving.getValue() && !hasInput) {
            return;
        }

        double perTick = bps.getValue() / 20.0D;
        Vec3 forward = forwardVector(player, input);
        Vec3 motion = player.getDeltaMovement();

        double vy = motion.y;
        if (killMomentumY.getValue()) {
            // Обнуляем накопление падения, чтобы визуальная физика не рвала анкер по вертикали.
            player.fallDistance = 0.0f;
            if (vy < -0.08D) vy = -0.08D;
        }

        player.setDeltaMovement(forward.x * perTick, vy, forward.z * perTick);
    }

    private static Vec3 forwardVector(LocalPlayer player, Vec2 input) {
        double yawRad = Math.toRadians(player.getYRot());
        double sin = -Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        double sx = input.x;
        double sy = input.y;
        double len = Math.sqrt(sx * sx + sy * sy);
        if (len < 1.0E-4D) {
            return new Vec3(sin, 0.0D, cos);
        }
        sx /= len;
        sy /= len;
        double wx = sx * cos + sy * sin;
        double wz = sy * cos - sx * sin;
        double wl = Math.sqrt(wx * wx + wz * wz);
        if (wl < 1.0E-6D) {
            return new Vec3(sin, 0.0D, cos);
        }
        return new Vec3(wx / wl, 0.0D, wz / wl);
    }

    private static double clamp(double value, double cap) {
        return Mth.clamp(value, -cap, cap);
    }
}
