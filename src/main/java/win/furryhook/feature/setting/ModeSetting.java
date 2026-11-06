package win.furryhook.feature.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
public final class ModeSetting extends Setting<String> {
    private List<String> modes;
    private Set<String> hiddenModes = Set.of();

    public ModeSetting(String name, String defaultValue, String... modes) {
        super(name, defaultValue);
        if (modes == null || modes.length == 0) {
            throw new IllegalArgumentException("Mode setting requires at least one mode");
        }

        this.modes = List.copyOf(new ArrayList<>(List.of(modes)));
        if (!containsMode(defaultValue)) {
            throw new IllegalArgumentException("Default mode must exist in modes: " + defaultValue);
        }
    }

    /**
     * Replaces the selectable mode list at runtime (e.g. a dropdown backed by files on disk).
     * If the current value no longer exists in the new list, it falls back to the first entry.
     */
    public void updateModes(List<String> newModes) {
        if (newModes == null || newModes.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(newModes);
        if (copy.equals(this.modes)) {
            return;
        }
        this.modes = copy;
        if (!containsMode(getValue())) {
            setValue(copy.get(0));
        }
    }

    /**
     * Keeps modes out of the menu dropdown, the module search and the cycle order while
     * leaving them fully selectable from a config — how a test mode ships without being
     * on display.
     */
    public ModeSetting hideModes(String... hidden) {
        Set<String> resolved = new LinkedHashSet<>();
        for (String mode : hidden) {
            int index = indexOf(mode);
            if (index == -1) {
                throw new IllegalArgumentException("Unknown mode '" + mode + "' for setting " + getName());
            }
            resolved.add(this.modes.get(index));
        }
        this.hiddenModes = Set.copyOf(resolved);
        return this;
    }

    public boolean isHidden(String mode) {
        int index = indexOf(mode);
        return index != -1 && this.hiddenModes.contains(this.modes.get(index));
    }

    /** Modes the menu offers; {@link #getModes()} still holds every mode a config may set. */
    public List<String> getVisibleModes() {
        if (this.hiddenModes.isEmpty()) {
            return this.modes;
        }
        List<String> visible = new ArrayList<>(this.modes.size());
        for (String mode : this.modes) {
            if (!this.hiddenModes.contains(mode)) {
                visible.add(mode);
            }
        }
        return List.copyOf(visible);
    }

    public boolean is(String mode) {
        return getValue().equalsIgnoreCase(mode);
    }

    /** Steps to the next mode on display, so a hidden one is only ever passed over. */
    public void cycle() {
        int current = indexOf(getValue());
        for (int offset = 1; offset <= modes.size(); offset++) {
            String candidate = modes.get((current + offset) % modes.size());
            if (!this.hiddenModes.contains(candidate)) {
                setValue(candidate);
                return;
            }
        }
    }

    public int indexOf(String mode) {
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).equalsIgnoreCase(mode)) {
                return i;
            }
        }
        return -1;
    }

    public boolean containsMode(String mode) {
        return indexOf(mode) != -1;
    }

    @Override
    protected String normalize(String value) {
        Objects.requireNonNull(value, "mode");
        int index = indexOf(value);
        if (index == -1) {
            throw new IllegalArgumentException("Unknown mode '" + value + "' for setting " + getName());
        }
        return modes.get(index);
    }

    @Override
    protected String normalizeWarningOption(String option) {
        Objects.requireNonNull(option, "mode");
        int index = indexOf(option);
        if (index == -1) {
            throw new IllegalArgumentException("Unknown mode '" + option + "' for setting " + getName());
        }
        return modes.get(index);
    }

    @Override
    protected JsonElement writeValue(String value) {
        return new JsonPrimitive(value);
    }

    @Override
    protected String readValue(JsonElement element) {
        String value = element.getAsString();
        int index = indexOf(value);
        return index == -1 ? getDefaultValue() : this.modes.get(index);
    }
}
