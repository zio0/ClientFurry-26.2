package win.furryhook.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import win.furryhook.menu.core.MenuConfigStore;
import win.furryhook.utils.AccountSwitcher;
import win.furryhook.utils.ConfigIO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The alt list behind the Alt Manager, persisted to config/furryhook/alts.json.
 *
 * The account actually in use is still owned by {@link AccountSwitcher} / the
 * "selectedAccount" key in menu.json - this store only decides which names exist and
 * keeps their last-used stamps.
 */
public final class AltStore {
    private static final Path PATH = ConfigIO.resolve("alts.json");
    private static final String KEY_ALTS = "alts";

    private static final List<Alt> ALTS = new ArrayList<>();

    private static boolean loaded;

    private AltStore() {
    }

    public static List<Alt> all() {
        load();
        return ALTS;
    }

    public static int size() {
        return all().size();
    }

    /** The alt matching the name the client is currently logged in with, or null. */
    public static Alt current() {
        String selected = MenuConfigStore.getString("selectedAccount", AccountSwitcher.DEFAULT_NAME);
        for (Alt alt : all()) {
            if (alt.name().equalsIgnoreCase(selected)) {
                return alt;
            }
        }
        return null;
    }

    public static boolean contains(String name) {
        for (Alt alt : all()) {
            if (alt.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>(size());
        for (Alt alt : all()) {
            names.add(alt.name());
        }
        return names;
    }

    /** Adds a new name at the top of the list. Returns null when it is invalid or a duplicate. */
    public static Alt add(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!Alt.isValidName(trimmed) || contains(trimmed)) {
            return null;
        }
        Alt alt = new Alt(trimmed, System.currentTimeMillis(), 0L);
        ALTS.add(0, alt);
        save();
        return alt;
    }

    public static void remove(Alt alt) {
        if (alt == null) {
            return;
        }
        load();
        if (ALTS.remove(alt)) {
            save();
        }
    }

    /** Switches the client session to this alt and stamps it as last used. */
    public static void login(Alt alt) {
        if (alt == null) {
            return;
        }
        AccountSwitcher.switchTo(alt.name());
        MenuConfigStore.save(data -> data.addProperty("selectedAccount", alt.name()));
        alt.markUsed();
        save();
    }

    public static void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Alt alt : ALTS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", alt.name());
            entry.addProperty("added", alt.addedAt());
            entry.addProperty("used", alt.lastUsedAt());
            array.add(entry);
        }
        root.add(KEY_ALTS, array);
        ConfigIO.write(PATH, root);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        JsonObject root = ConfigIO.read(PATH);
        if (root != null) {
            JsonElement element = root.get(KEY_ALTS);
            if (element != null && element.isJsonArray()) {
                for (JsonElement raw : element.getAsJsonArray()) {
                    if (!raw.isJsonObject()) {
                        continue;
                    }
                    JsonObject entry = raw.getAsJsonObject();
                    String name = entry.has("name") ? entry.get("name").getAsString() : "";
                    if (!Alt.isValidName(name) || containsLoaded(name)) {
                        continue;
                    }
                    long added = entry.has("added") ? entry.get("added").getAsLong() : 0L;
                    long used = entry.has("used") ? entry.get("used").getAsLong() : 0L;
                    ALTS.add(new Alt(name, added, used));
                }
            }
        }

        // First run: seed the list with whatever the client is logged in as so the
        // manager is never an empty box.
        String selected = MenuConfigStore.getString("selectedAccount", AccountSwitcher.DEFAULT_NAME);
        if (!Alt.isValidName(selected)) {
            selected = AccountSwitcher.DEFAULT_NAME;
        }
        if (!containsLoaded(selected)) {
            ALTS.add(0, new Alt(selected, System.currentTimeMillis(), System.currentTimeMillis()));
            save();
        }
    }

    private static boolean containsLoaded(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Alt alt : ALTS) {
            if (alt.name().toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }
}
