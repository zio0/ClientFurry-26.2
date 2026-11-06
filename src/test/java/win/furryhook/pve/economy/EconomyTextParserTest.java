package win.furryhook.pve.economy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyTextParserTest {
    @Test
    void parsesGroupedAndSuffixedAmounts() {
        assertEquals(List.of(1_234_567L), EconomyTextParser.amounts("Цена: $1 234 567"));
        assertEquals(List.of(1_500_000L), EconomyTextParser.amounts("Стоимость 1,5 млн"));
        assertEquals(List.of(2_250_000_000L), EconomyTextParser.amounts("price 2.25b"));
    }

    @Test
    void findsBalanceBesideLocalizedLabel() {
        assertEquals(
                987_654L,
                EconomyTextParser.amountNearAnyLabel(
                        "§aБаланс: §f987 654 монет",
                        "balance",
                        "баланс",
                        "монет"
                ).orElseThrow()
        );
        assertTrue(EconomyTextParser.containsAny("Бaлaнc", "баланс"));
    }

    @Test
    void validatesNamesAndBuildsOnlyWhitelistedCommands() {
        assertTrue(EconomyTextParser.isSafePlayerName("Player_42"));
        assertFalse(EconomyTextParser.isSafePlayerName("Player 42"));
        assertEquals("clan invest 1200", EconomyCommands.clanInvest(1_200).orElseThrow());
        assertEquals("pay Player_42 500", EconomyCommands.pay("Player_42", 500).orElseThrow());
        assertTrue(EconomyCommands.auctionSearch("ah", "золотой блок").isPresent());
        assertTrue(EconomyCommands.auctionSearch("ah", "gold/block").isEmpty());
        assertTrue(EconomyCommands.pay("Player\n/op", 500).isEmpty());
    }
}
