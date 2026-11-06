package win.furryhook.pve.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import win.furryhook.pve.server.ServerProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MineProfiles {
    public static final String MODE_AUTO = "Auto";
    public static final String MODE_FUNTIME = "FunTime";
    public static final String MODE_HOLYWORLD = "HolyWorld";
    public static final String MODE_NONE = "None";

    public static final Profile FUNTIME_MAIN = profile(
            "funtime-main",
            ServerProfile.FUNTIME,
            -86, 72, -5,
            -66, 81, 15
    );

    public static final Profile HOLYWORLD_FIRST = profile(
            "holyworld-first",
            ServerProfile.HOLYWORLD,
            43, 73, 38,
            61, 82, 56
    );

    public static final Profile HOLYWORLD_SECOND = profile(
            "holyworld-second",
            ServerProfile.HOLYWORLD,
            43, 70, 74,
            61, 82, 92
    );

    private static final List<Profile> HOLYWORLD_MINES = List.of(
            HOLYWORLD_FIRST,
            HOLYWORLD_SECOND
    );

    private MineProfiles() {
    }

    public static Optional<Profile> select(String mode,
                                           ServerProfile detected,
                                           BlockPos playerPosition) {
        String normalized = mode == null
                ? MODE_AUTO.toLowerCase(Locale.ROOT)
                : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "funtime" -> Optional.of(FUNTIME_MAIN);
            case "holyworld" -> nearestHolyWorld(playerPosition);
            case "none", "generic" -> Optional.empty();
            default -> switch (detected) {
                case FUNTIME -> Optional.of(FUNTIME_MAIN);
                case HOLYWORLD -> nearestHolyWorld(playerPosition);
                default -> Optional.empty();
            };
        };
    }

    public static List<Profile> holyWorldMines() {
        return HOLYWORLD_MINES;
    }

    private static Optional<Profile> nearestHolyWorld(BlockPos position) {
        if (position == null) {
            return Optional.of(HOLYWORLD_FIRST);
        }
        return HOLYWORLD_MINES.stream()
                .min(Comparator.comparingDouble(profile -> profile.distanceToSqr(position)));
    }

    private static Profile profile(String id,
                                   ServerProfile server,
                                   int minX,
                                   int minY,
                                   int minZ,
                                   int maxX,
                                   int maxY,
                                   int maxZ) {
        return new Profile(
                id,
                server,
                new BlockPos(
                        Math.min(minX, maxX),
                        Math.min(minY, maxY),
                        Math.min(minZ, maxZ)
                ),
                new BlockPos(
                        Math.max(minX, maxX),
                        Math.max(minY, maxY),
                        Math.max(minZ, maxZ)
                )
        );
    }

    public record Profile(
            String id,
            ServerProfile server,
            BlockPos min,
            BlockPos max
    ) {
        public AABB box() {
            return new AABB(
                    min.getX(),
                    min.getY(),
                    min.getZ(),
                    max.getX() + 1.0D,
                    max.getY() + 1.0D,
                    max.getZ() + 1.0D
            );
        }

        public double distanceToSqr(BlockPos position) {
            return box().distanceToSqr(Vec3.atCenterOf(position));
        }

        public int topY() {
            return max.getY();
        }

        public int bottomY() {
            return min.getY();
        }
    }
}
