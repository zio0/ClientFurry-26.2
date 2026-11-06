package win.furryhook.utils.combat;

import win.furryhook.feature.setting.MultiSelectSetting;

public final class CombatTargets {
    public static final String PLAYERS = "Players";
    public static final String FRIENDS = "Friends";
    public static final String NAKED_PLAYERS = "Naked Players";
    public static final String INVISIBLES = "Invisibles";
    public static final String MONSTERS = "Monsters";
    public static final String ANIMALS = "Animals";
    public static final String VILLAGERS = "Villagers";

    private CombatTargets() {
    }

    public static TargetFilter.TargetFilterBuilder groups(TargetFilter.TargetFilterBuilder builder, MultiSelectSetting targets) {
        return builder
                .targetsPlayers(targets.isSelected(PLAYERS))
                .targetsFriends(targets.isSelected(FRIENDS))
                .targetsNakedPlayers(targets.isSelected(NAKED_PLAYERS))
                .targetsInvisibles(targets.isSelected(INVISIBLES))
                .targetsMonsters(targets.isSelected(MONSTERS))
                .targetsAnimals(targets.isSelected(ANIMALS))
                .targetsVillagers(targets.isSelected(VILLAGERS));
    }
}
