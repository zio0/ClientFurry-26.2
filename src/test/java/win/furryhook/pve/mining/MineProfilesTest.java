package win.furryhook.pve.mining;

import net.minecraft.core.BlockPos;
import win.furryhook.pve.server.ServerProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineProfilesTest {
    @Test
    void profilesRemainNormalizedInclusiveCuboidsAfterWipeEdits() {
        assertNormalized(MineProfiles.FUNTIME_MAIN);
        assertNormalized(MineProfiles.HOLYWORLD_FIRST);
        assertNormalized(MineProfiles.HOLYWORLD_SECOND);
    }

    @Test
    void autoModeUsesDetectedServerProfile() {
        assertEquals(
                MineProfiles.FUNTIME_MAIN,
                MineProfiles.select(
                        MineProfiles.MODE_AUTO,
                        ServerProfile.FUNTIME,
                        BlockPos.ZERO
                ).orElseThrow()
        );
        assertTrue(MineProfiles.select(
                MineProfiles.MODE_AUTO,
                ServerProfile.GENERIC,
                BlockPos.ZERO
        ).isEmpty());
    }

    @Test
    void holyWorldUsesTheNearestOfBothMines() {
        assertEquals(
                MineProfiles.HOLYWORLD_FIRST,
                MineProfiles.select(
                        MineProfiles.MODE_HOLYWORLD,
                        ServerProfile.GENERIC,
                        center(MineProfiles.HOLYWORLD_FIRST)
                ).orElseThrow()
        );
        assertEquals(
                MineProfiles.HOLYWORLD_SECOND,
                MineProfiles.select(
                        MineProfiles.MODE_HOLYWORLD,
                        ServerProfile.GENERIC,
                        center(MineProfiles.HOLYWORLD_SECOND)
                ).orElseThrow()
        );
    }

    private static void assertNormalized(MineProfiles.Profile profile) {
        assertTrue(profile.min().getX() <= profile.max().getX());
        assertTrue(profile.min().getY() <= profile.max().getY());
        assertTrue(profile.min().getZ() <= profile.max().getZ());
    }

    private static BlockPos center(MineProfiles.Profile profile) {
        return new BlockPos(
                (profile.min().getX() + profile.max().getX()) / 2,
                (profile.min().getY() + profile.max().getY()) / 2,
                (profile.min().getZ() + profile.max().getZ()) / 2
        );
    }
}
