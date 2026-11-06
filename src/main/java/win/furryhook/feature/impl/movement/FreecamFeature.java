package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * Отвязывает рендер-камеру от энтити игрока. Позиция/поворот камеры хранятся
 * тут статически и читаются {@code CameraMixin#alignWithEntity}, которая при
 * активном фрикаме полностью отменяет ванильный привязочный код и подставляет
 * наши {@link #camPos}/{@link #camYaw}/{@link #camPitch}. Сам игрок при этом
 * не двигается и не спит — обычный ввод (WASD) на сервер не уходит, вместо
 * этого {@code KeyboardInputMixin} глушит реальный Input, пока фрикам включён.
 * Полёт камеры считается по тем же keybind'ам напрямую в {@link #onTick}.
 */
public final class FreecamFeature extends Feature implements MinecraftContext {

    private static volatile boolean active;
    private static volatile Vec3 camPos = Vec3.ZERO;
    private static volatile Vec3 prevCamPos = Vec3.ZERO;
    private static volatile float camYaw;
    private static volatile float camPitch;

    public final NumberSetting speed = register(new NumberSetting(
            "Speed", 1.0D, 0.1D, 5.0D, 0.1D, ""
    ));

    public FreecamFeature() {
        super("Freecam", "Свободная камера, игрок остаётся на месте", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static boolean isActive() {
        return active;
    }

    public static Vec3 interpolatedPosition(float partialTick) {
        return prevCamPos.lerp(camPos, partialTick);
    }

    public static float yaw() {
        return camYaw;
    }

    public static float pitch() {
        return camPitch;
    }

    public static boolean onMouseTurn(double yawDelta, double pitchDelta) {
        if (!active) {
            return false;
        }
        camYaw += (float) yawDelta * 0.15F;
        camPitch = net.minecraft.util.Mth.clamp(camPitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
        return true;
    }

    @Override
    protected void onEnable() {
        LocalPlayer player = player();
        if (player == null) {
            active = false;
            return;
        }
        camPos = player.getEyePosition();
        prevCamPos = camPos;
        camYaw = player.getYRot();
        camPitch = player.getXRot();
        active = true;
    }

    @Override
    protected void onDisable() {
        active = false;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (!active) {
            return;
        }

        prevCamPos = camPos;

        double step = speed.getValue();
        double yawRad = Math.toRadians(camYaw);
        double sin = -Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double dx = 0.0D;
        double dy = 0.0D;
        double dz = 0.0D;

        if (mc.options.keyUp.isDown()) {
            dx += sin;
            dz += cos;
        }
        if (mc.options.keyDown.isDown()) {
            dx -= sin;
            dz -= cos;
        }
        if (mc.options.keyLeft.isDown()) {
            dx -= cos;
            dz += sin;
        }
        if (mc.options.keyRight.isDown()) {
            dx += cos;
            dz -= sin;
        }
        if (mc.options.keyJump.isDown()) {
            dy += 1.0D;
        }
        if (mc.options.keyShift.isDown()) {
            dy -= 1.0D;
        }

        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 1.0E-4D) {
            dx = dx / length * step;
            dz = dz / length * step;
        }
        dy *= step;

        if (dx != 0.0D || dy != 0.0D || dz != 0.0D) {
            camPos = camPos.add(dx, dy, dz);
        }
    }
}
