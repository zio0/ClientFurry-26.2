package win.furryhook.pve.mining;

import java.text.Normalizer;
import java.util.Locale;

public record MineTimer(String nextType, int initialSeconds, long startedAtMillis) {
    public MineTimer {
        nextType = sanitizeDisplayText(nextType);
        nextType = nextType.isBlank() ? "Unknown" : nextType;
        initialSeconds = Math.max(0, initialSeconds);
    }

    public int secondsLeft(long nowMillis) {
        long elapsed = Math.max(0L, nowMillis - this.startedAtMillis) / 1_000L;
        return (int) Math.max(0L, this.initialSeconds - elapsed);
    }

    public boolean isExpired(long nowMillis) {
        return secondsLeft(nowMillis) == 0;
    }

    public String formattedTime(long nowMillis) {
        int seconds = secondsLeft(nowMillis);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    static String sanitizeDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?i)§[0-9A-FK-ORX]", "");
        StringBuilder result = new StringBuilder(normalized.length());
        boolean lastWasSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == 0x2013 || codePoint == 0x2014 || codePoint == 0x2212) {
                codePoint = '-';
            } else if (codePoint == 0x2018 || codePoint == 0x2019) {
                codePoint = '\'';
            } else if (codePoint == 0x201C || codePoint == 0x201D) {
                codePoint = '"';
            }

            if (Character.isWhitespace(codePoint)) {
                if (!lastWasSpace && !result.isEmpty()) {
                    result.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (isUiGlyph(codePoint)) {
                result.appendCodePoint(codePoint);
                lastWasSpace = false;
            }
        }
        return result.toString().trim();
    }

    private static boolean isUiGlyph(int codePoint) {
        return codePoint >= 32 && codePoint <= 126
                || codePoint >= 0x0400 && codePoint <= 0x045F;
    }
}
