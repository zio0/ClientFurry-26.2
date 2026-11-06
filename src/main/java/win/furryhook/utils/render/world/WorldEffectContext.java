package win.furryhook.utils.render.world;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

public record WorldEffectContext(
        LevelRenderState levelRenderState,
        CameraRenderState cameraRenderState,
        float tickDelta
) {
}
