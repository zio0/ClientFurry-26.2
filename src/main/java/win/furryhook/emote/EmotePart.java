package win.furryhook.emote;

import java.util.Locale;

/**
 * Body part a keyframe can target. Matches kosmx PlayerAnimator's bone names (the format the
 * bundled emotes were authored in) - "torso" is an older alias for "body" seen in some files.
 */
public enum EmotePart {
    HEAD,
    BODY,
    RIGHT_ARM,
    LEFT_ARM,
    RIGHT_LEG,
    LEFT_LEG;

    public static EmotePart fromJsonKey(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "head" -> HEAD;
            case "torso", "body" -> BODY;
            case "rightarm" -> RIGHT_ARM;
            case "leftarm" -> LEFT_ARM;
            case "rightleg" -> RIGHT_LEG;
            case "leftleg" -> LEFT_LEG;
            // "cape" and "leftItem"/"rightItem" move the cape model and held-item pose, which
            // this engine doesn't drive (no cape-bone or item-pose hook) - skip silently rather
            // than pretending they're one of the above.
            default -> null;
        };
    }
}
