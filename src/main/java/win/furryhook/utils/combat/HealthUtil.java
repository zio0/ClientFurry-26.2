package win.furryhook.utils.combat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import win.furryhook.feature.impl.misc.ScoreboardHealthFeature;

public final class HealthUtil {
    private HealthUtil() {
    }

    /**
     * Vanilla health (health + absorption) for the entity, unless
     * {@link ScoreboardHealthFeature} is active and the entity is a player with a
     * below-name scoreboard objective, in which case the real value is read from it.
     */
    public static float getHealth(LivingEntity entity) {
        float hp = entity.getHealth() + entity.getAbsorptionAmount();

        if (ScoreboardHealthFeature.isActive() && entity instanceof Player player) {
            Float fromBoard = readScoreboardHealth(player);
            if (fromBoard != null) {
                hp = fromBoard;
            }
        }

        return Mth.clamp(hp, 0.0F, entity.getMaxHealth() + entity.getAbsorptionAmount());
    }

    private static Float readScoreboardHealth(Player player) {
        Scoreboard scoreboard = player.level().getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME);
        if (objective == null) {
            return null;
        }

        ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(player, objective);
        if (info == null) {
            return null;
        }

        Component text = info.formatValue(objective.numberFormatOrDefault(StyledFormat.NO_STYLE));
        String raw = ChatFormatting.stripFormatting(text.getString());
        if (raw == null) {
            return null;
        }
        raw = raw.trim().replace(",", ".");
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
