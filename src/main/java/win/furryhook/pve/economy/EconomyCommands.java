package win.furryhook.pve.economy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class EconomyCommands {
    private static final Pattern AUCTION_ROOT = Pattern.compile("(?:ah|auction)");
    private static final Pattern QUERY = Pattern.compile("[\\p{L}\\p{N}_ .,'’\\-]{1,40}");

    private EconomyCommands() {
    }

    public static Optional<String> auctionRoot(String root) {
        String normalized = root(root);
        return AUCTION_ROOT.matcher(normalized).matches()
                ? Optional.of(normalized)
                : Optional.empty();
    }

    public static Optional<String> auctionSearch(String root, String query) {
        Optional<String> safeRoot = auctionRoot(root);
        String safeQuery = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        if (safeRoot.isEmpty() || !QUERY.matcher(safeQuery).matches()) {
            return Optional.empty();
        }
        return Optional.of(safeRoot.get() + " search " + safeQuery);
    }

    public static Optional<String> auctionSell(String root, long price) {
        return auctionRoot(root)
                .filter(ignored -> price > 0L && price <= Integer.MAX_VALUE)
                .map(safeRoot -> safeRoot + " sell " + price);
    }

    public static String shop() {
        return "shop";
    }

    public static Optional<String> clanInvest(long amount) {
        return amount > 0L && amount <= Integer.MAX_VALUE
                ? Optional.of("clan invest " + amount)
                : Optional.empty();
    }

    public static Optional<String> pay(String playerName, long amount) {
        return EconomyTextParser.isSafePlayerName(playerName)
                && amount > 0L
                && amount <= Integer.MAX_VALUE
                ? Optional.of("pay " + playerName + " " + amount)
                : Optional.empty();
    }

    private static String root(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }
}
