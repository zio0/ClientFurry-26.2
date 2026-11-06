package win.furryhook.context;

import lombok.Getter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class RotationContext implements MinecraftContext {
    private static final float MOUSE_TURN_SCALE = 0.15F;

    @Getter
    private static float freeYaw;
    @Getter
    private static float freePitch;
    @Getter
    private static boolean active;
    private static boolean hasLastAngle;

    @Getter
    private static float serverYaw;
    @Getter
    private static float serverPitch;

    private static float lastYaw;
    private static float lastPitch;
    private static float lastHeadYaw;
    private static float lastBodyYaw;

    private RotationContext() {
    }

    public static void apply(float yawDelta, float pitchDelta) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (!active) {
            freeYaw = player.getYRot();
            freePitch = player.getXRot();
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
            active = true;
        }

        rememberRenderAngles(player);

        // Keep serverYaw continuous so Grim AC sees continuous mouse motion
        serverYaw = serverYaw + yawDelta;
        serverPitch = Mth.clamp(serverPitch + pitchDelta, -90.0F, 90.0F);

        player.setYRot(serverYaw);
        player.setXRot(serverPitch);
        player.yHeadRot = serverYaw;
        player.yBodyRot = serverYaw;
    }

    /**
     * Sets exact target server rotation. Calculates shortest angular delta to prevent
     * 360-degree modulo wrap jumps (Grim Anti-Cheat AimModulo360 check).
     */
    public static void setRotation(float yaw, float pitch) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (!active) {
            freeYaw = player.getYRot();
            freePitch = player.getXRot();
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
            active = true;
        }

        float targetPitch = Mth.clamp(pitch, -90.0F, 90.0F);

        // Compute shortest angular delta relative to continuous serverYaw
        float deltaYaw = Mth.wrapDegrees(yaw - serverYaw);
        float deltaPitch = targetPitch - serverPitch;

        // Quantize rotation step using exact vanilla mouse sensitivity GCD formula
        double gcdStep = gcdStep();
        if (gcdStep >= 1.0E-4D) {
            deltaYaw = (float) (Math.round((double) deltaYaw / gcdStep) * gcdStep);
            deltaPitch = (float) (Math.round((double) deltaPitch / gcdStep) * gcdStep);
        }

        apply(deltaYaw, deltaPitch);
    }

    /**
     * Vanilla's mouse-sensitivity quantisation step: every rotation delta the client can
     * physically send is a multiple of it. Rotations that shape their own steps need the
     * same grid, or they hand {@link #setRotation} sub-step deltas that round away to zero.
     */
    public static double gcdStep() {
        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        return f * f * f * 1.2D;
    }

    public static void rotateTo(LocalPlayer player, Entity target) {
        Vec3 delta = target.getEyePosition().subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));

        setRotation(yaw, pitch);
    }

    public static void rotateToPosition(LocalPlayer player, Vec3 pos) {
        Vec3 delta = pos.subtract(player.getEyePosition());

        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));

        setRotation(yaw, pitch);
    }

    public static float getFovToEntity(LocalPlayer player, Entity entity) {
        double diffX = entity.getX() - player.getX();
        double diffZ = entity.getZ() - player.getZ();

        float yaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0D);
        float deltaYaw = Mth.wrapDegrees(yaw - (active ? serverYaw : player.getYRot()));

        return Math.abs(deltaYaw);
    }

    public static void syncFreeLook(float yaw, float pitch) {
        if (active) {
            return;
        }

        freeYaw = yaw;
        freePitch = pitch;
    }

    public static boolean onMouseTurn(double yawInput, double pitchInput) {
        if (!active) {
            return false;
        }

        freeYaw += (float) yawInput * MOUSE_TURN_SCALE;
        freePitch = Mth.clamp(
                freePitch + (float) pitchInput * MOUSE_TURN_SCALE,
                -90.0F,
                90.0F
        );
        return true;
    }

    public static void applyRenderInterpolation() {
        if (!active || !hasLastAngle) {
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null) {
            hasLastAngle = false;
            return;
        }

        player.yRotO = lastYaw;
        player.xRotO = lastPitch;
        player.yHeadRotO = lastHeadYaw;
        player.yBodyRotO = lastBodyYaw;
    }

    /**
     * Smoothly rotates server angles back to player's view over multiple ticks,
     * then disengages active state completely.
     */
    public static void disengage() {
        if (!active) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            clear();
            return;
        }

        float deltaYaw = Mth.wrapDegrees(player.getYRot() - serverYaw);
        float deltaPitch = player.getXRot() - serverPitch;

        if (Math.abs(deltaYaw) < 2.0F && Math.abs(deltaPitch) < 2.0F) {
            clear();
        } else {
            rememberRenderAngles(player);
            serverYaw = serverYaw + deltaYaw * 0.4F;
            serverPitch = Mth.clamp(serverPitch + deltaPitch * 0.4F, -90.0F, 90.0F);

            player.setYRot(serverYaw);
            player.setXRot(serverPitch);
            player.yHeadRot = serverYaw;
            player.yBodyRot = serverYaw;
        }
    }

    /**
     * Instantly disengages rotation spoofing and restores player control to freeYaw/freePitch.
     *
     * While active, the rendered camera follows freeYaw/freePitch (real mouse input) but the
     * player's actual rotation is spoofed to serverYaw/serverPitch (facing the target). Once
     * inactive, the camera falls back to reading the player's actual rotation, so that rotation
     * must be synced to freeYaw/freePitch here first or the view visibly snaps to wherever the
     * spoofed rotation last pointed.
     */
    public static void clear() {
        LocalPlayer player = mc.player;

        if (active && player != null) {
            player.setYRot(freeYaw);
            player.setXRot(freePitch);
            player.yHeadRot = freeYaw;
            player.yBodyRot = freeYaw;
            player.yRotO = freeYaw;
            player.xRotO = freePitch;
            player.yHeadRotO = freeYaw;
            player.yBodyRotO = freeYaw;
        }

        active = false;
        hasLastAngle = false;

        if (player != null) {
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
            freeYaw = player.getYRot();
            freePitch = player.getXRot();
        }
    }

    private static void rememberRenderAngles(LocalPlayer player) {
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
        lastHeadYaw = player.yHeadRot;
        lastBodyYaw = player.yBodyRot;
        hasLastAngle = true;
    }
}