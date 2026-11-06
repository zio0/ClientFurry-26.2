package win.furryhook.feature.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import lombok.Getter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
public final class MultiSelectSetting extends Setting<Set<String>> {
    private final List<String> options;

    public MultiSelectSetting(String name, Collection<String> defaultValue, String... options) {
        super(name, new LinkedHashSet<>(defaultValue));
        if (options == null || options.length == 0) {
            throw new IllegalArgumentException("MultiSelect setting requires at least one option");
        }

        this.options = List.of(options);
        for (String selected : defaultValue) {
            ensureOptionExists(selected);
        }
    }

    /**
     * Hot path: overlay features query this per entity per frame. {@link #resolveOption} walks the
     * option list twice (once to validate, once to canonicalise) with {@code equalsIgnoreCase};
     * matching the caller's literal by identity/equals first collapses that to a single cheap scan.
     */
    public boolean isSelected(String option) {
        Set<String> selected = getValue();
        for (String candidate : options) {
            if (candidate.equals(option)) {
                return !selected.isEmpty() && selected.contains(candidate);
            }
        }
        return selected.contains(resolveOption(option));
    }

    public void select(String option) {
        LinkedHashSet<String> selected = new LinkedHashSet<>(getValue());
        selected.add(resolveOption(option));
        setValue(selected);
    }

    public void deselect(String option) {
        LinkedHashSet<String> selected = new LinkedHashSet<>(getValue());
        selected.remove(resolveOption(option));
        setValue(selected);
    }

    public void toggle(String option) {
        if (isSelected(option)) {
            deselect(option);
            return;
        }
        select(option);
    }

    @Override
    protected Set<String> normalize(Set<String> value) {
        Objects.requireNonNull(value, "selectedOptions");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String option : value) {
            normalized.add(resolveOption(option));
        }
        return Set.copyOf(normalized);
    }

    @Override
    protected String normalizeWarningOption(String option) {
        return resolveOption(option);
    }

    @Override
    protected JsonElement writeValue(Set<String> value) {
        JsonArray array = new JsonArray();
        for (String option : value) {
            array.add(option);
        }
        return array;
    }

    @Override
    protected Set<String> readValue(JsonElement element) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            values.add(entry.getAsString());
        }
        return values;
    }

    private String resolveOption(String option) {
        ensureOptionExists(option);
        for (String candidate : options) {
            if (candidate.equalsIgnoreCase(option)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown option '" + option + "' for setting " + getName());
    }

    private void ensureOptionExists(String option) {
        for (String candidate : options) {
            if (candidate.equalsIgnoreCase(option)) {
                return;
            }
        }
        throw new IllegalArgumentException("Unknown option '" + option + "' for setting " + getName());
    }
}
