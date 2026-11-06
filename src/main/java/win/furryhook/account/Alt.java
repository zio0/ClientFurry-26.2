package win.furryhook.account;

import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One saved offline account. The UUID is derived exactly the way the vanilla offline
 * login derives it, so the head shown here is the head the server will see.
 */
public final class Alt {
    public static final String NAME_PATTERN = "^[a-zA-Z0-9_]{3,16}$";

    private final String name;
    private final UUID uuid;
    private final long addedAt;

    private long lastUsedAt;
    private Identifier skin;

    public Alt(String name, long addedAt, long lastUsedAt) {
        this.name = name;
        this.uuid = offlineUuid(name);
        this.addedAt = addedAt;
        this.lastUsedAt = lastUsedAt;
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches(NAME_PATTERN);
    }

    public String name() {
        return this.name;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public long addedAt() {
        return this.addedAt;
    }

    public long lastUsedAt() {
        return this.lastUsedAt;
    }

    public void markUsed() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    /** Default Steve/Alex variant for this UUID - offline accounts have no uploaded skin. */
    public Identifier skin() {
        Identifier cached = this.skin;
        if (cached == null) {
            cached = DefaultPlayerSkin.get(this.uuid).body().texturePath();
            this.skin = cached;
        }
        return cached;
    }

    /** "2m ago" / "3d ago" / "never used", for the second line of a list row. */
    public String lastUsedLabel() {
        if (this.lastUsedAt <= 0L) {
            return "never used";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - this.lastUsedAt) / 1000L);
        if (seconds < 60L) {
            return "used just now";
        }
        if (seconds < 3600L) {
            return "used " + (seconds / 60L) + "m ago";
        }
        if (seconds < 86_400L) {
            return "used " + (seconds / 3600L) + "h ago";
        }
        return "used " + (seconds / 86_400L) + "d ago";
    }
}
