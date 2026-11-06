package win.furryhook.utils.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EntityEspStateCache {
    private static volatile List<EntityRenderState> frameStates = Collections.emptyList();

    private EntityEspStateCache() {
    }

    public static void capture(List<EntityRenderState> states) {
        frameStates = states == null || states.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(states);
    }

    public static List<EntityRenderState> currentStates() {
        return frameStates;
    }

    public static void clear() {
        frameStates = Collections.emptyList();
    }
}
