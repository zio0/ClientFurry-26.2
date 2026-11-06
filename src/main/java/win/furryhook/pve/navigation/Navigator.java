package win.furryhook.pve.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

public interface Navigator {
    boolean isAvailable();

    void begin(NavigationOptions options);

    void pathTo(BlockPos position, int radius);

    void mine(int quantity, Block... blocks);

    void setMineBounds(BlockPos min, BlockPos max);

    default void setMineRenderColor(int argb) {
    }

    default void setMineAoeLevel(int level) {
    }

    boolean isPathing();

    boolean isMining();

    List<BlockPos> miningTargets();

    Optional<Double> estimatedTicksToGoal();

    Optional<BlockPos> currentGoal();

    String diagnostics();

    void cancel();

    void end();
}
