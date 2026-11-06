package win.furryhook.pve.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EconomyTextParser {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([+]?[0-9][0-9\\s\\u00a0_'’.,]*)\\s*"
                    + "(тыс(?:\\.|яч[аи]?)?|млн|миллион(?:а|ов)?|млрд|миллиард(?:а|ов)?|[kmbкмб])?"
                    + "(?!\\p{L})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern SAFE_PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private EconomyTextParser() {
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                .replace('ё', 'е')
                .replace('Ё', 'Е');
        return FORMATTING.matcher(normalized)
                .replaceAll("")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String foldCyrillicConfusables(String value) {
        return normalize(value)
                .replace('a', 'а')
                .replace('c', 'с')
                .replace('e', 'е')
                .replace('o', 'о')
                .replace('p', 'р')
                .replace('x', 'х')
                .replace('y', 'у');
    }

    public static boolean containsAny(String value, String... needles) {
        String normalized = normalize(value);
        String folded = foldCyrillicConfusables(value);
        for (String needle : needles) {
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isEmpty()
                    && (normalized.contains(normalizedNeedle)
                    || folded.contains(foldCyrillicConfusables(normalizedNeedle)))) {
                return true;
            }
        }
        return false;
    }

    public static List<Long> amounts(String value) {
        ArrayList<Long> result = new ArrayList<>();
        Matcher matcher = AMOUNT.matcher(normalize(value));
        while (matcher.find()) {
            parseAmount(matcher.group(1), matcher.group(2))
                    .ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public static OptionalLong largestAmount(String value) {
        long largest = -1L;
        for (long amount : amounts(value)) {
            largest = Math.max(largest, amount);
        }
        return largest < 0L ? OptionalLong.empty() : OptionalLong.of(largest);
    }

    public static OptionalLong amountNearAnyLabel(String value, String... labels) {
        String normalized = normalize(value);
        long largest = -1L;
        for (String label : labels) {
            String target = normalize(label);
            int from = 0;
            while (!target.isEmpty()) {
                int index = normalized.indexOf(target, from);
                if (index < 0) {
                    break;
                }
                int windowStart = Math.max(0, index - 48);
                int windowEnd = Math.min(normalized.length(), index + target.length() + 64);
                OptionalLong candidate = largestAmount(normalized.substring(windowStart, windowEnd));
                if (candidate.isPresent()) {
                    largest = Math.max(largest, candidate.getAsLong());
                }
                from = index + target.length();
            }
        }
        return largest < 0L ? OptionalLong.empty() : OptionalLong.of(largest);
    }

    public static boolean isSafePlayerName(String value) {
        return value != null && SAFE_PLAYER_NAME.matcher(value).matches();
    }

    public static int positiveInt(String value, int fallback, int maximum) {
        if (value == null || !value.matches("\\d+")) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(maximum, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static OptionalLong parseAmount(String token, String suffix) {
        long multiplier = multiplier(suffix);
        String compact = token.replaceAll("[\\s\\u00a0_'’]", "");
        if (compact.isEmpty()) {
            return OptionalLong.empty();
        }

        try {
            BigDecimal number;
            int separator = Math.max(compact.lastIndexOf('.'), compact.lastIndexOf(','));
            boolean decimalSuffix = multiplier > 1L
                    && separator >= 0
                    && compact.length() - separator - 1 <= 2;
            if (decimalSuffix) {
                String integerPart = compact.substring(0, separator).replaceAll("\\D", "");
                String fractionalPart = compact.substring(separator + 1).replaceAll("\\D", "");
                if (integerPart.isEmpty() || fractionalPart.isEmpty()) {
                    return OptionalLong.empty();
                }
                number = new BigDecimal(integerPart + "." + fractionalPart);
            } else {
                String digits = compact.replaceAll("\\D", "");
                if (digits.isEmpty()) {
                    return OptionalLong.empty();
                }
                number = new BigDecimal(digits);
            }
            BigDecimal scaled = number.multiply(BigDecimal.valueOf(multiplier));
            if (scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                return OptionalLong.of(Long.MAX_VALUE);
            }
            return OptionalLong.of(scaled.setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException | NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private static long multiplier(String suffix) {
        String normalized = normalize(suffix);
        if (normalized.isEmpty()) {
            return 1L;
        }
        if (normalized.startsWith("тыс") || normalized.equals("k") || normalized.equals("к")) {
            return 1_000L;
        }
        if (normalized.startsWith("млрд")
                || normalized.startsWith("миллиард")
                || normalized.equals("b")
                || normalized.equals("б")) {
            return 1_000_000_000L;
        }
        if (normalized.startsWith("млн")
                || normalized.startsWith("миллион")
                || normalized.equals("m")
                || normalized.equals("м")) {
            return 1_000_000L;
        }
        return 1L;
    }
}
