package win.furryhook.pve.mining;

import win.furryhook.pve.server.ServerProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningServerAdaptersTest {
    @Test
    void parsesFunTimeHologramWithoutFormattingCodes() {
        MiningServerAdapter adapter =
                MiningServerAdapters.forProfile(ServerProfile.FUNTIME);

        MineTimer timer = adapter.parseMineTimer(List.of(
                "§dАвто-Шахта",
                "§fСледующая: §6Легендарная",
                "§7Обновление через:",
                "01:09"
        ), 10_000L).orElseThrow();

        assertEquals("Легендарная", timer.nextType());
        assertEquals(69, timer.initialSeconds());
        assertEquals("01:08", timer.formattedTime(11_000L));
    }

    @Test
    void rejectsIncompleteServerHologram() {
        MiningServerAdapter adapter =
                MiningServerAdapters.forProfile(ServerProfile.FUNTIME);

        assertTrue(adapter.parseMineTimer(
                List.of("Следующая: Обычная", "00:30"),
                0L
        ).isEmpty());
    }

    @Test
    void stripsServerIconsUnsupportedByUiFont() {
        MineTimer timer = new MineTimer(
                "§6\uE123◆ Легендарная \uD83D\uDD25",
                60,
                0L
        );

        assertEquals("Легендарная", timer.nextType());
    }

    @Test
    void genericParserAndCommandsRemainIndependent() {
        MiningServerAdapter generic =
                MiningServerAdapters.forProfile(ServerProfile.GENERIC);
        MineTimer timer = generic.parseMineTimer(
                List.of("Next mine: Diamond", "Refresh: 02:05"),
                1_000L
        ).orElseThrow();

        assertEquals(125, timer.initialSeconds());
        assertTrue(generic.warpMineCommand().isEmpty());
    }
}
