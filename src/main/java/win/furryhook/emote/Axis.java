package win.furryhook.emote;

import java.util.Locale;

/** One animated channel of a body part: three position axes, three rotation axes. */
public enum Axis {
    POS_X,
    POS_Y,
    POS_Z,
    ROT_PITCH,
    ROT_YAW,
    ROT_ROLL;

    public static Axis fromJsonKey(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "x" -> POS_X;
            case "y" -> POS_Y;
            case "z" -> POS_Z;
            case "pitch" -> ROT_PITCH;
            case "yaw" -> ROT_YAW;
            case "roll" -> ROT_ROLL;
            default -> null;
        };
    }

    public boolean isRotation() {
        return this == ROT_PITCH || this == ROT_YAW || this == ROT_ROLL;
    }
}
