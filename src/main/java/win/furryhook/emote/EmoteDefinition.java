package win.furryhook.emote;

import net.minecraft.resources.Identifier;

import java.util.function.Supplier;

/** Static metadata for one bundled emote, plus a lazily-parsed/cached animation. */
public final class EmoteDefinition {
    private final String id;
    private final String displayName;
    private final Identifier icon;
    private final Supplier<EmoteAnimation> loader;
    private EmoteAnimation cached;

    EmoteDefinition(String id, String displayName, Identifier icon) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.loader = () -> EmoteJsonLoader.load(id, displayName, "/assets/furryhook/emotes/" + id + ".json");
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public Identifier icon() {
        return this.icon;
    }

    public synchronized EmoteAnimation animation() {
        if (this.cached == null) {
            this.cached = this.loader.get();
        }
        return this.cached;
    }
}
