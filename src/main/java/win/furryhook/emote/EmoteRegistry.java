package win.furryhook.emote;

import win.furryhook.utils.render.Textures;

import java.util.List;

/** The fixed set of emotes bundled with the client. */
public final class EmoteRegistry {
    public static final List<EmoteDefinition> ALL = List.of(
            new EmoteDefinition("waving", "Waving", Textures.Emotes.WAVING),
            new EmoteDefinition("clap", "Clap", Textures.Emotes.CLAP),
            new EmoteDefinition("point", "Point", Textures.Emotes.POINT),
            new EmoteDefinition("here", "Here", Textures.Emotes.HERE),
            new EmoteDefinition("palm", "Facepalm", Textures.Emotes.PALM),
            new EmoteDefinition("crying", "Crying", Textures.Emotes.CRYING),
            new EmoteDefinition("backflip", "Backflip", Textures.Emotes.BACKFLIP),
            new EmoteDefinition("twerk", "Twerk", Textures.Emotes.TWERK),
            new EmoteDefinition("kazotsky_kick", "Kazotsky Kick", Textures.Emotes.KAZOTSKY_KICK),
            new EmoteDefinition("club_penguin_dance", "Club Penguin", Textures.Emotes.CLUB_PENGUIN_DANCE),
            new EmoteDefinition("roblox_potion_dance", "Potion Dance", Textures.Emotes.ROBLOX_POTION_DANCE)
    );

    private EmoteRegistry() {
    }

    public static EmoteDefinition byId(String id) {
        for (EmoteDefinition definition : ALL) {
            if (definition.id().equals(id)) {
                return definition;
            }
        }
        return null;
    }
}
