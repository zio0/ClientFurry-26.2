package win.furryhook.mixin.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import win.furryhook.emote.EmotePart;
import win.furryhook.emote.EmotePoseCarrier;

import java.util.EnumMap;

/** Carries one frame's sampled emote pose from {@link AvatarRendererMixin} to {@link PlayerModelMixin}. */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements EmotePoseCarrier {
    @Unique
    private EnumMap<EmotePart, float[]> furryhook$emotePose;

    @Override
    public EnumMap<EmotePart, float[]> furryhook$getEmotePose() {
        return this.furryhook$emotePose;
    }

    @Override
    public void furryhook$setEmotePose(EnumMap<EmotePart, float[]> pose) {
        this.furryhook$emotePose = pose;
    }
}
