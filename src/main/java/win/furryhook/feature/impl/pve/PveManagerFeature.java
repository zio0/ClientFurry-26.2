package win.furryhook.feature.impl.pve;

import net.minecraft.client.Minecraft;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.server.ServerProfile;

import java.util.Locale;

public final class PveManagerFeature extends Feature {
    public static final PveManagerFeature INSTANCE = new PveManagerFeature();

    public static final String SERVER_AUTO = "Auto";
    public static final String SERVER_GENERIC = "Generic";
    public static final String SERVER_FUNTIME = "FunTime";
    public static final String SERVER_HOLYWORLD = "HolyWorld";
    public static final String SERVER_REALLYWORLD = "ReallyWorld";

    public final BooleanSetting rotate = register(new BooleanSetting(
            "Rotate", true
    ));
    public final ModeSetting serverProfile = register(new ModeSetting(
            "Server Profile",
            SERVER_AUTO,
            SERVER_AUTO,
            SERVER_GENERIC,
            SERVER_FUNTIME,
            SERVER_HOLYWORLD,
            SERVER_REALLYWORLD
    ));
    public final TextSetting homeName = register(new TextSetting(
            "Home Name", "", 32
    ));
    public final TextSetting clanName = register(new TextSetting(
            "Clan Name", "", 32
    ));
    public final NumberSetting anarchy = register(new NumberSetting(
            "Anarchy", 1.0D, 1.0D, 999.0D, 1.0D, ""
    ));
    public final NumberSetting minimumHealth = register(new NumberSetting(
            "Minimum Health", 12.0D, 1.0D, 20.0D, 1.0D, ""
    ));
    public final NumberSetting minimumToolDurability = register(new NumberSetting(
            "Minimum Tool Durability", 15.0D, 1.0D, 80.0D, 1.0D, "%"
    ));
    public final BooleanSetting pauseNearPlayers = register(new BooleanSetting(
            "Pause Near Players", true
    ));
    public final NumberSetting playerRadius = register(new NumberSetting(
            "Player Radius", 16.0D, 0.0D, 96.0D, 1.0D, " blocks"
    ).visibleWhen(this.pauseNearPlayers::getValue));
    public final BooleanSetting debugLogging = register(new BooleanSetting(
            "Debug Logging", true
    ));
    /**
     * Gone from here on purpose. The panel it used to drive is now one of HudFeature's Elements,
     * switched on and off in the same place as every other HUD element instead of from a flag in a
     * different feature that nobody would think to look in.
     */


    private PveManagerFeature() {
        super(
                "PveManager",
                "Shared server, identity, rotation and safety settings for PvE",
                FeatureCategory.MISC,
                BindSetting.UNBOUND
        );
    }

    @Override
    public boolean isToggleable() {
        return false;
    }

    @Override
    public boolean supportsBinds() {
        return false;
    }

    public ServerProfile resolveServerProfile(Minecraft client) {
        String normalized = this.serverProfile.getValue()
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "generic" -> ServerProfile.GENERIC;
            case "funtime" -> ServerProfile.FUNTIME;
            case "holyworld" -> ServerProfile.HOLYWORLD;
            case "reallyworld" -> ServerProfile.REALLYWORLD;
            default -> ServerProfile.detect(client);
        };
    }

    public String resolvedHomeName() {
        return this.homeName.getValue().trim();
    }

    public String resolvedClanName() {
        return this.clanName.getValue().trim();
    }

    public int resolvedAnarchy() {
        return this.anarchy.getValue().intValue();
    }

    public NavigationOptions configureNavigation(NavigationOptions options) {
        return options.withViewRotation(this.rotate.getValue());
    }
}
