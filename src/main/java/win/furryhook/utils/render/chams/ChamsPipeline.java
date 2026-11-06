package win.furryhook.utils.render.chams;

import net.minecraft.client.renderer.state.level.LevelRenderState;
import win.furryhook.feature.impl.visual.ChamsFeature;

public final class ChamsPipeline {
    private final ChamsMaskRenderer maskRenderer = new ChamsMaskRenderer();
    private final ChamsCompositeEffect composite = new ChamsCompositeEffect();

    public void render(LevelRenderState levelRenderState, ChamsFeature feature) {
        this.maskRenderer.renderGroups(
                levelRenderState,
                feature,
                frame -> this.composite.render(frame, feature)
        );
    }

    public void release() {
        this.maskRenderer.release();
        this.composite.release();
    }
}
