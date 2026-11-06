package win.furryhook.utils.combat;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class TargetFilter {
    private final boolean targetsPlayers;
    private final boolean targetsFriends;
    private final boolean targetsNakedPlayers;
    private final boolean targetsInvisibles;
    private final boolean targetsVillagers;
    private final boolean targetsMonsters;
    private final boolean targetsAnimals;
    private final boolean targetsTeams;
    private final double distanceWeight;
    private final double healthWeight;
    private final double armorWeight;
    private final double fovWeight;
}
