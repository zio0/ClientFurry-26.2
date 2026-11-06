package win.furryhook.utils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nickname roller for the Alt Manager dice button: furry vocabulary crossed with
 * current brainrot slang, kept inside Mojang's [A-Za-z0-9_]{3,16} rule.
 */
public final class RandomNick {
    private static final int MAX_LENGTH = 16;

    private static final String[] FURRY = {
            "murr", "floof", "fluff", "snoot", "boop", "paw", "tail", "awoo", "bork",
            "mrrp", "nyaa", "purr", "scritch", "snep", "kitsune", "vixen", "proto",
            "sergal", "husky", "fox", "wolf", "yote", "raccoon", "feral", "uwu", "owo",
            "blep", "toebeans", "wag"
    };

    private static final String[] MEME = {
            "aura", "rizz", "sigma", "skibidi", "gyatt", "mewing", "delulu", "brainrot",
            "crashout", "yap", "glaze", "clanker", "chopped", "cooked", "npc", "goofy",
            "moots", "huzz", "lowtaper", "chat", "ragebait", "cap"
    };

    private static final String[] ADJECTIVE = {
            "silly", "based", "feral", "damp", "wet", "cursed", "holy", "mid", "peak",
            "sussy", "crusty", "unhinged", "ghostly", "buff"
    };

    private static final String[] SUFFIX = {
            "maxxing", "pilled", "core", "gaming", "era", "arc", "coded", "tm"
    };

    private static final int[] NUMBER = {67, 47, 7, 88, 2007, 9000};

    /** Cumulative weights for the seven shapes below - see {@link #build(int)}. */
    private static final int[] PATTERN_WEIGHTS = {25, 45, 60, 75, 85, 93, 100};

    private static String lastRolled = "";

    private RandomNick() {
    }

    public static String next(Collection<String> taken) {
        Set<String> used = new HashSet<>();
        if (taken != null) {
            for (String name : taken) {
                if (name != null) {
                    used.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }

        for (int attempt = 0; attempt < 96; attempt++) {
            String candidate = build(rollPattern());
            if (candidate.length() < 3 || candidate.length() > MAX_LENGTH) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (used.contains(lower) || lower.equals(lastRolled.toLowerCase(Locale.ROOT))) {
                continue;
            }
            lastRolled = candidate;
            return candidate;
        }

        // Every shape collided: fall back to a plain furry word plus digits.
        String fallback = capitalize(pick(FURRY)) + ThreadLocalRandom.current().nextInt(100, 1000);
        lastRolled = fallback;
        return fallback;
    }

    private static int rollPattern() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        for (int i = 0; i < PATTERN_WEIGHTS.length; i++) {
            if (roll < PATTERN_WEIGHTS[i]) {
                return i;
            }
        }
        return 0;
    }

    private static String build(int pattern) {
        return switch (pattern) {
            case 0 -> capitalize(pick(FURRY)) + capitalize(pick(MEME));
            case 1 -> pick(MEME) + "_" + pick(FURRY);
            case 2 -> capitalize(pick(ADJECTIVE)) + capitalize(pick(FURRY)) + number();
            case 3 -> pick(FURRY) + pick(SUFFIX);
            case 4 -> capitalize(pick(FURRY)) + "OfOhio";
            case 5 -> "x" + pick(FURRY) + "x";
            default -> capitalize(pick(MEME)) + capitalize(pick(FURRY)) + number();
        };
    }

    private static String number() {
        return Integer.toString(NUMBER[ThreadLocalRandom.current().nextInt(NUMBER.length)]);
    }

    private static String pick(String[] pool) {
        return pool[ThreadLocalRandom.current().nextInt(pool.length)];
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
