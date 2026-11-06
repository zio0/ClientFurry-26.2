package win.furryhook.feature.impl.pve;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ModeratorDetector {
    private static final Pattern FORMATTING_CODE =
            Pattern.compile("(?i)\u00a7[0-9A-FK-ORX]");
    private static final Pattern ROLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:"
                    + "admin(?:istrator)?|moderator|mod|staff|helper|curator"
                    + "|админ(?:истратор)?|модер(?:атор)?|хелпер|куратор|персонал|стаж[её]р"
                    + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private ModeratorDetector() {
    }

    public static Optional<String> find(Minecraft client, LocalPlayer self) {
        if (client == null || self == null || client.getConnection() == null) {
            return Optional.empty();
        }
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            if (self.getUUID().equals(info.getProfile().id())) {
                continue;
            }
            if (containsRole(roleText(info))) {
                String name = info.getProfile().name();
                if (name != null) {
                    return Optional.of(name);
                }
            }
        }
        if (client.level != null) {
            for (Player player : client.level.players()) {
                if (player == self || self.getUUID().equals(player.getUUID())) {
                    continue;
                }
                if (containsRole(roleText(player.getDisplayName(), player.getTeam()))) {
                    String name = player.getGameProfile().name();
                    if (name != null) {
                        return Optional.of(name);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static boolean containsRole(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = FORMATTING_CODE.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT);
        return ROLE.matcher(normalized).find();
    }

    private static String roleText(PlayerInfo info) {
        return roleText(info.getTabListDisplayName(), info.getTeam());
    }

    private static String roleText(Component displayName, PlayerTeam team) {
        StringBuilder text = new StringBuilder();
        append(text, displayName);
        if (team != null) {
            append(text, team.getPlayerPrefix());
            append(text, team.getPlayerSuffix());
            text.append(' ').append(team.getName());
        }
        return text.toString();
    }

    private static void append(StringBuilder target, Component component) {
        if (component != null) {
            target.append(' ').append(component.getString());
        }
    }
}
