package win.furryhook.emote;

import java.util.EnumMap;

/** Duck interface mixed onto {@code AvatarRenderState} to carry a sampled emote pose one frame. */
public interface EmotePoseCarrier {
    EnumMap<EmotePart, float[]> furryhook$getEmotePose();

    void furryhook$setEmotePose(EnumMap<EmotePart, float[]> pose);
}
