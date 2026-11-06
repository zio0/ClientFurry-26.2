package win.furryhook.pve.mining;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ContainerClusterScanner {
    private BlockPos center;
    private int radius;
    private int verticalRadius;
    private int cursor;
    private int volume;
    private Set<Block> targets = Set.of();
    private final List<BlockPos> matches = new ArrayList<>();

    public void begin(BlockPos center, int radius, Set<Block> targets) {
        this.center = center == null ? null : center.immutable();
        this.radius = Math.max(1, radius);
        this.verticalRadius = Math.min(12, this.radius);
        int width = this.radius * 2 + 1;
        int height = this.verticalRadius * 2 + 1;
        this.volume = width * width * height;
        this.cursor = 0;
        this.targets = targets == null ? Set.of() : Set.copyOf(targets);
        this.matches.clear();
    }

    public Optional<Result> scan(ClientLevel level, int budget) {
        if (level == null || this.center == null || this.targets.isEmpty()) {
            return Optional.empty();
        }
        int width = this.radius * 2 + 1;
        int height = this.verticalRadius * 2 + 1;
        int remaining = Math.max(1, budget);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        while (this.cursor < this.volume && remaining-- > 0) {
            int index = this.cursor++;
            int xIndex = index % width;
            int zIndex = (index / width) % width;
            int yIndex = (index / (width * width)) % height;
            mutable.set(
                    this.center.getX() + xIndex - this.radius,
                    this.center.getY() + yIndex - this.verticalRadius,
                    this.center.getZ() + zIndex - this.radius
            );
            if (level.hasChunkAt(mutable)
                    && this.targets.contains(level.getBlockState(mutable).getBlock())) {
                this.matches.add(mutable.immutable());
            }
        }
        if (this.cursor < this.volume) {
            return Optional.empty();
        }
        return Optional.of(new Result(this.center, List.copyOf(this.matches)));
    }

    public boolean isRunning() {
        return this.center != null && this.cursor < this.volume;
    }

    public void reset() {
        this.center = null;
        this.cursor = 0;
        this.volume = 0;
        this.targets = Set.of();
        this.matches.clear();
    }

    public record Result(BlockPos scanCenter, List<BlockPos> matches) {
        public Optional<BlockPos> nearestTo(BlockPos position) {
            if (position == null) {
                return Optional.empty();
            }
            return this.matches.stream().min((first, second) -> Double.compare(
                    first.distSqr(position),
                    second.distSqr(position)
            ));
        }
    }
}
