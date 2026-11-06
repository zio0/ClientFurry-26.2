package win.furryhook.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import win.furryhook.feature.impl.pve.PveManagerFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PveManagerConfigMigration {
    private PveManagerConfigMigration() {
    }

    static boolean migrate(JsonObject features, PveManagerFeature manager) {
        if (features == null
                || manager == null
                || features.has(manager.getName())) {
            return false;
        }

        boolean migrated = false;

        Boolean rotate = booleanValue(features, "Nuker", "Rotate");
        if (rotate != null) {
            manager.rotate.setValue(rotate);
            migrated = true;
        }

        String server = preferredServer(
                stringValue(features, "BaseFinder", "Server Mode"),
                stringValue(features, "MineHelper", "Server Mode")
        );
        if (server != null) {
            manager.serverProfile.setValue(server);
            migrated = true;
        }

        String home = firstNonBlank(
                stringValue(features, "AutoMine", "Home Name"),
                stringValue(features, "AppleFarmer", "Home Name")
        );
        if (home != null) {
            manager.homeName.setValue(home);
            migrated = true;
        }

        Double anarchy = numberValue(features, "BaseFinder", "Anarchy");
        if (anarchy != null) {
            manager.anarchy.setValue(anarchy);
            migrated = true;
        }

        Double minimumHealth = maximum(
                numberValue(features, "AutoMine", "Minimum Health"),
                numberValue(features, "BaseFinder", "Minimum Health")
        );
        if (minimumHealth != null) {
            manager.minimumHealth.setValue(minimumHealth);
            migrated = true;
        }

        Double minimumDurability = maximum(
                numberValue(features, "AutoMine", "Minimum Pickaxe Durability"),
                numberValue(features, "BaseFinder", "Minimum Pickaxe Durability"),
                numberValue(features, "MineHelper", "Pickaxe Guard")
        );
        if (minimumDurability != null) {
            manager.minimumToolDurability.setValue(minimumDurability);
            migrated = true;
        }

        Double autoMineRadius = numberValue(features, "AutoMine", "Avoid Players");
        Double baseFinderRadius = numberValue(features, "BaseFinder", "Avoid Players");
        Double playerRadius = maximum(autoMineRadius, baseFinderRadius);
        if (playerRadius != null) {
            manager.playerRadius.setValue(playerRadius);
            migrated = true;
        }

        Boolean autoMinePause = booleanValue(
                features,
                "AutoMine",
                "Pause Near Players"
        );
        if (autoMinePause != null || baseFinderRadius != null) {
            manager.pauseNearPlayers.setValue(Boolean.TRUE.equals(autoMinePause)
                    || baseFinderRadius != null && baseFinderRadius > 0.0D);
            migrated = true;
        }

        Boolean debug = booleanValue(features, "AutoMine", "Debug Logging");
        if (debug != null) {
            manager.debugLogging.setValue(debug);
            migrated = true;
        }

        return migrated;
    }

    private static String preferredServer(String... values) {
        String auto = null;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String canonical = canonicalServer(value);
            if (canonical == null) {
                continue;
            }
            if (!PveManagerFeature.SERVER_AUTO.equals(canonical)) {
                return canonical;
            }
            auto = canonical;
        }
        return auto;
    }

    private static String canonicalServer(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> PveManagerFeature.SERVER_AUTO;
            case "generic" -> PveManagerFeature.SERVER_GENERIC;
            case "funtime" -> PveManagerFeature.SERVER_FUNTIME;
            case "holyworld" -> PveManagerFeature.SERVER_HOLYWORLD;
            case "reallyworld" -> PveManagerFeature.SERVER_REALLYWORLD;
            default -> null;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Double maximum(Double... values) {
        List<Double> valid = new ArrayList<>();
        for (Double value : values) {
            if (value != null && Double.isFinite(value)) {
                valid.add(value);
            }
        }
        return valid.stream().max(Double::compareTo).orElse(null);
    }

    private static Boolean booleanValue(JsonObject features,
                                        String feature,
                                        String setting) {
        JsonElement value = setting(features, feature, setting);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : null;
    }

    private static Double numberValue(JsonObject features,
                                      String feature,
                                      String setting) {
        JsonElement value = setting(features, feature, setting);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : null;
    }

    private static String stringValue(JsonObject features,
                                      String feature,
                                      String setting) {
        JsonElement value = setting(features, feature, setting);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static JsonElement setting(JsonObject features,
                                       String feature,
                                       String setting) {
        JsonElement featureElement = features.get(feature);
        if (featureElement == null || !featureElement.isJsonObject()) {
            return null;
        }
        JsonElement settingsElement = featureElement.getAsJsonObject().get("settings");
        if (settingsElement == null || !settingsElement.isJsonObject()) {
            return null;
        }
        return settingsElement.getAsJsonObject().get(setting);
    }
}
