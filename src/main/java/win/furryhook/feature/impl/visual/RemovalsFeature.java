package win.furryhook.feature.impl.visual;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.MultiSelectSetting;

import java.util.Set;

public final class RemovalsFeature extends Feature {
    private static RemovalsFeature instance;

    private final MultiSelectSetting overlay = register(new MultiSelectSetting(
            "Overlay",
            Set.of("Fire", "Bad Effects", "Effects Overlay"),
            "Fire",
            "Bad Effects",
            "Shaking",
            "Totem Overlay",
            "Scoreboard",
            "Boss Bar",
            "Effects Overlay"
    ));
    private final MultiSelectSetting world = register(new MultiSelectSetting(
            "World",
            Set.of("Weather"),
            "Weather",
            "Totem Particles",
            "Effects"
    ));
    private final MultiSelectSetting sounds = register(new MultiSelectSetting(
            "Sounds",
            Set.of(),
            "Hit",
            "Hurt",
            "Totem",
            "Step",
            "Eat",
            "Firework",
            "Warden"
    ));

    public RemovalsFeature() {
        super("Removals", "Hides distracting overlays, world effects, particles, and sounds.", FeatureCategory.VISUAL, BindSetting.UNBOUND);
        instance = this;
    }

    public static boolean shouldRemoveFireOverlay() {
        return active() && instance.overlay.isSelected("Fire");
    }

    public static boolean shouldRemoveBadEffectsVisuals() {
        return active() && instance.overlay.isSelected("Bad Effects");
    }

    public static boolean shouldRemoveShaking() {
        return active() && instance.overlay.isSelected("Shaking");
    }

    public static boolean shouldRemoveTotemOverlay() {
        return active() && instance.overlay.isSelected("Totem Overlay");
    }

    public static boolean shouldRemoveScoreboard() {
        return active() && instance.overlay.isSelected("Scoreboard");
    }

    public static boolean shouldRemoveBossBar() {
        return active() && instance.overlay.isSelected("Boss Bar");
    }

    public static boolean shouldRemoveEffectsOverlay() {
        return active() && instance.overlay.isSelected("Effects Overlay");
    }

    public static boolean shouldRemoveWeather() {
        return active() && instance.world.isSelected("Weather");
    }

    public static boolean shouldRemoveParticle(ParticleOptions options) {
        if (!active() || options == null) {
            return false;
        }
        if (instance.world.isSelected("Totem Particles") && options.getType() == ParticleTypes.TOTEM_OF_UNDYING) {
            return true;
        }
        return instance.world.isSelected("Effects");
    }

    public static boolean shouldRemoveSound(SoundEvent soundEvent) {
        if (!active() || soundEvent == null) {
            return false;
        }

        String soundPath = soundEvent.location().getPath();
        if (instance.sounds.isSelected("Totem") && soundPath.contains("totem")) return true;
        if (instance.sounds.isSelected("Firework") && soundPath.contains("firework")) return true;
        if (instance.sounds.isSelected("Step") && soundPath.contains("step")) return true;
        if (instance.sounds.isSelected("Eat") && (soundPath.contains("eat") || soundPath.contains("drink") || soundPath.contains("burp"))) return true;
        if (instance.sounds.isSelected("Hit") && (soundPath.contains("attack") || soundPath.contains("hit"))) return true;
        if (instance.sounds.isSelected("Warden") && (soundPath.contains("warden") || soundPath.contains("sculk_sensor") || soundPath.contains("sculk_shrieker") || soundPath.contains("shriek"))) {
            return true;
        }
        return instance.sounds.isSelected("Hurt") && soundPath.contains("hurt");
    }

    private static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
