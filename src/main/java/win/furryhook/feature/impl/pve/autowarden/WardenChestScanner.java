package win.furryhook.feature.impl.pve.autowarden;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WardenChestScanner {
    private static final long OBSERVATION_TTL_MILLIS = 30L * 60L * 1000L;
    private static final long FAILED_TTL_MILLIS = 90_000L;
    private static final int TIMER_CHEST_SEARCH_RADIUS = 2;

    private final Map<BlockPos, MutableChest> known = new HashMap<>();
    private final Map<BlockPos, Long> failedUntil = new HashMap<>();
    private final Set<BlockPos> looted = new HashSet<>();

    List<ChestObservation> scanLootChests(ClientLevel level,
                                          LocalPlayer player,
                                          int horizontalRadius,
                                          int crowdRadius,
                                          long nowMillis) {
        int radius = Math.max(8, Math.min(128, horizontalRadius));
        AABB entityBounds = player.getBoundingBox().inflate(radius, 32.0D, radius);
        for (ArmorStand stand : level.getEntitiesOfClass(
                ArmorStand.class,
                entityBounds,
                entity -> entity.hasCustomName() && entity.getCustomName() != null
        )) {
            AutoWardenParsers.parseChestTimer(stand.getCustomName().getString())
                    .ifPresent(timer -> findContainerNear(level, stand.blockPosition())
                            .ifPresent(position -> observeTimer(position, timer, nowMillis)));
        }

        int directRadius = Math.min(48, radius);
        BlockPos origin = player.blockPosition();
        for (int x = -directRadius; x <= directRadius; x++) {
            for (int z = -directRadius; z <= directRadius; z++) {
                for (int y = -10; y <= 10; y++) {
                    BlockPos position = origin.offset(x, y, z);
                    if (!level.hasChunkAt(position) || !isContainer(level.getBlockState(position))) {
                        continue;
                    }
                    BlockPos canonical = canonicalContainer(level, position);
                    this.known.computeIfAbsent(canonical, ignored ->
                            new MutableChest(canonical, -1, nowMillis, false, false));
                }
            }
        }

        this.known.values().removeIf(value -> nowMillis - value.lastSeenAt > OBSERVATION_TTL_MILLIS);
        this.failedUntil.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        List<ChestObservation> result = new ArrayList<>();
        for (MutableChest value : this.known.values()) {
            if (this.looted.contains(value.position)
                    || this.failedUntil.getOrDefault(value.position, 0L) > nowMillis) {
                continue;
            }
            int crowd = nearbyPlayers(level, player, value.position, crowdRadius);
            result.add(value.snapshot(crowd));
        }
        result.sort(Comparator.comparingLong(value -> value.position().asLong()));
        return List.copyOf(result);
    }

    Optional<ChestObservation> selectBest(Collection<ChestObservation> candidates,
                                          Vec3 origin,
                                          int maxWaitSeconds,
                                          boolean avoidCrowded,
                                          double crowdPenalty,
                                          long nowMillis) {
        ChestObservation selected = null;
        double selectedScore = Double.POSITIVE_INFINITY;
        for (ChestObservation candidate : candidates) {
            int remaining = candidate.remainingSeconds(nowMillis);
            if (candidate.timerKnown() && remaining > Math.max(0, maxWaitSeconds)) {
                continue;
            }
            double distance = Vec3.atCenterOf(candidate.position()).distanceTo(origin);
            double unknownPenalty = candidate.timerKnown() ? 0.0D : 30.0D;
            double score = remaining * 4.0D
                    + distance
                    + unknownPenalty
                    + (avoidCrowded ? candidate.nearbyPlayers() * Math.max(0.0D, crowdPenalty) : 0.0D);
            if (score < selectedScore) {
                selected = candidate;
                selectedScore = score;
            }
        }
        return Optional.ofNullable(selected);
    }

    List<StorageChest> scanStorage(ClientLevel level,
                                   BlockPos center,
                                   int radius,
                                   String restockKeyword,
                                   String sellKeyword) {
        int scanRadius = Math.max(4, Math.min(48, radius));
        String restock = AutoWardenParsers.normalize(restockKeyword);
        String sell = AutoWardenParsers.normalize(sellKeyword);
        Map<BlockPos, StorageChest> result = new LinkedHashMap<>();
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int z = -scanRadius; z <= scanRadius; z++) {
                for (int y = -8; y <= 8; y++) {
                    BlockPos position = center.offset(x, y, z);
                    if (!level.hasChunkAt(position) || !isContainer(level.getBlockState(position))) {
                        continue;
                    }
                    BlockPos canonical = canonicalContainer(level, position);
                    if (result.containsKey(canonical)) {
                        continue;
                    }
                    String sign = signText(level, canonical);
                    boolean signed = !sign.isBlank();
                    StorageKind kind;
                    if (signed && !sell.isBlank() && sign.contains(sell)) {
                        kind = StorageKind.SELL;
                    } else if (signed && (restock.isBlank() || sign.contains(restock))) {
                        kind = StorageKind.RESTOCK;
                    } else if (signed) {
                        kind = StorageKind.OTHER_SIGNED;
                    } else {
                        kind = StorageKind.DEPOSIT;
                    }
                    result.put(canonical, new StorageChest(canonical, kind, sign));
                }
            }
        }
        return result.values().stream()
                .sorted(Comparator.comparingDouble(value -> value.position().distSqr(center)))
                .toList();
    }

    void markLooted(BlockPos position) {
        if (position != null) {
            this.looted.add(position.immutable());
            MutableChest value = this.known.get(position);
            if (value != null) {
                value.stolen = true;
            }
        }
    }

    void markFailed(BlockPos position, long nowMillis) {
        if (position != null) {
            this.failedUntil.put(position.immutable(), nowMillis + FAILED_TTL_MILLIS);
        }
    }

    void clearLootedCycle() {
        this.looted.clear();
        this.known.values().forEach(value -> value.stolen = false);
    }

    void clearWorld() {
        this.known.clear();
        this.failedUntil.clear();
        this.looted.clear();
    }

    private void observeTimer(BlockPos position,
                              AutoWardenParsers.TimerReading timer,
                              long nowMillis) {
        BlockPos immutable = position.immutable();
        MutableChest existing = this.known.get(immutable);
        if (existing == null) {
            this.known.put(immutable, new MutableChest(
                    immutable,
                    timer.remainingSeconds(),
                    nowMillis,
                    true,
                    timer.openNow()
            ));
            return;
        }
        existing.observedSeconds = timer.remainingSeconds();
        existing.observedAt = nowMillis;
        existing.lastSeenAt = nowMillis;
        existing.timerKnown = true;
        existing.openNow = timer.openNow();
    }

    private static Optional<BlockPos> findContainerNear(ClientLevel level, BlockPos stand) {
        BlockPos selected = null;
        double selectedDistance = Double.POSITIVE_INFINITY;
        for (int x = -TIMER_CHEST_SEARCH_RADIUS; x <= TIMER_CHEST_SEARCH_RADIUS; x++) {
            for (int z = -TIMER_CHEST_SEARCH_RADIUS; z <= TIMER_CHEST_SEARCH_RADIUS; z++) {
                for (int y = -3; y <= 1; y++) {
                    BlockPos position = stand.offset(x, y, z);
                    if (!isContainer(level.getBlockState(position))) {
                        continue;
                    }
                    double distance = position.distSqr(stand);
                    if (distance < selectedDistance) {
                        selected = canonicalContainer(level, position);
                        selectedDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(selected);
    }

    private static int nearbyPlayers(ClientLevel level,
                                     LocalPlayer self,
                                     BlockPos chest,
                                     int radius) {
        if (radius <= 0) {
            return 0;
        }
        AABB bounds = new AABB(chest).inflate(radius);
        return level.getEntitiesOfClass(Player.class, bounds, player -> player != self).size();
    }

    private static boolean isContainer(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL;
    }

    private static BlockPos canonicalContainer(ClientLevel level, BlockPos position) {
        Block block = level.getBlockState(position).getBlock();
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) {
            return position.immutable();
        }
        BlockPos selected = position;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = position.relative(direction);
            if (level.getBlockState(adjacent).getBlock() == block
                    && adjacent.asLong() < selected.asLong()) {
                selected = adjacent;
            }
        }
        return selected.immutable();
    }

    private static String signText(ClientLevel level, BlockPos container) {
        StringBuilder result = new StringBuilder();
        appendAdjacentSigns(level, container, result);
        Block block = level.getBlockState(container).getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = container.relative(direction);
                if (level.getBlockState(adjacent).getBlock() == block) {
                    appendAdjacentSigns(level, adjacent, result);
                }
            }
        }
        return AutoWardenParsers.normalize(result.toString());
    }

    private static void appendAdjacentSigns(ClientLevel level,
                                            BlockPos container,
                                            StringBuilder output) {
        for (Direction direction : Direction.values()) {
            BlockEntity entity = level.getBlockEntity(container.relative(direction));
            if (entity instanceof SignBlockEntity sign) {
                appendSignSide(sign, "getFrontText", output);
                appendSignSide(sign, "getBackText", output);
            }
        }
    }

    private static void appendSignSide(SignBlockEntity sign,
                                       String accessor,
                                       StringBuilder output) {
        try {
            Method sideMethod = SignBlockEntity.class.getMethod(accessor);
            Object side = sideMethod.invoke(sign);
            Method messages = findMethod(side.getClass(), "getMessages", boolean.class);
            if (messages != null) {
                appendComponents(messages.invoke(side, false), output);
                return;
            }
            Method message = findMethod(side.getClass(), "getMessage", int.class, boolean.class);
            if (message != null) {
                for (int line = 0; line < 4; line++) {
                    appendComponent(message.invoke(side, line, false), output);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void appendComponents(Object value, StringBuilder output) {
        if (value == null || !value.getClass().isArray()) {
            return;
        }
        for (int index = 0; index < Array.getLength(value); index++) {
            appendComponent(Array.get(value, index), output);
        }
    }

    private static void appendComponent(Object value, StringBuilder output) {
        if (value instanceof Component component) {
            output.append(' ').append(component.getString());
        }
    }

    record ChestObservation(
            BlockPos position,
            int observedSeconds,
            long observedAtMillis,
            boolean timerKnown,
            boolean openNow,
            boolean stolen,
            int nearbyPlayers
    ) {
        int remainingSeconds(long nowMillis) {
            if (!this.timerKnown || this.openNow) {
                return 0;
            }
            long elapsed = Math.max(0L, nowMillis - this.observedAtMillis) / 1000L;
            return (int) Math.max(0L, this.observedSeconds - elapsed);
        }
    }

    enum StorageKind {
        DEPOSIT,
        RESTOCK,
        SELL,
        OTHER_SIGNED
    }

    record StorageChest(BlockPos position, StorageKind kind, String signText) {
    }

    private static final class MutableChest {
        private final BlockPos position;
        private int observedSeconds;
        private long observedAt;
        private long lastSeenAt;
        private boolean timerKnown;
        private boolean openNow;
        private boolean stolen;

        private MutableChest(BlockPos position,
                             int observedSeconds,
                             long observedAt,
                             boolean timerKnown,
                             boolean openNow) {
            this.position = position;
            this.observedSeconds = observedSeconds;
            this.observedAt = observedAt;
            this.lastSeenAt = observedAt;
            this.timerKnown = timerKnown;
            this.openNow = openNow;
        }

        private ChestObservation snapshot(int nearbyPlayers) {
            return new ChestObservation(
                    this.position,
                    this.observedSeconds,
                    this.observedAt,
                    this.timerKnown,
                    this.openNow,
                    this.stolen,
                    nearbyPlayers
            );
        }
    }
}
