package win.furryhook.feature.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;

import java.util.List;
import java.util.Set;

public final class ChamsFeature extends Feature {
    public static final String TARGET_PLAYERS = "Players";
    public static final String TARGET_HOSTILE = "Hostile";
    public static final String TARGET_PASSIVE = "Passive";
    public static final String TARGET_ITEMS = "Items";

    public static final String EFFECT_SHADER_FILL = "Shader Fill";
    public static final String EFFECT_SOLID = "Solid";
    public static final String EFFECT_GLASS = "Glass";
    public static final String EFFECT_OUTLINE = "Outline";
    public static final String EFFECT_GLOW = "Glow";

    public static final String SHADER_PLASMA = "Plasma";
    public static final String SHADER_NEBULA = "Nebula";

    public static final String MODE_EXTERNAL = "External";
    public static final String MODE_INTERNAL = "Internal";
    public static final String MODE_BOTH = "Both";

    public final MultiSelectSetting targets = register(new MultiSelectSetting(
            "Targets",
            Set.of(TARGET_PLAYERS, TARGET_HOSTILE),
            TARGET_PLAYERS,
            TARGET_HOSTILE,
            TARGET_PASSIVE,
            TARGET_ITEMS
    ).configKey("render.chams.targets"));
    public final MultiSelectSetting effects = register(new MultiSelectSetting(
            "Effect",
            List.of(EFFECT_SHADER_FILL, EFFECT_GLOW),
            EFFECT_SHADER_FILL,
            EFFECT_SOLID,
            EFFECT_GLASS,
            EFFECT_OUTLINE,
            EFFECT_GLOW
    ).configKey("render.chams.effect"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.chams.colorMode"));
    public final ColorSetting color = register(new ColorSetting(
            "Visible Color",
            0xFF7986CB
    ).configKey("render.chams.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced",
            false
    ).asExpander().configKey("render.chams.advanced"));

    public final NumberSetting distance = register(new NumberSetting(
            "Distance",
            96.0D,
            8.0D,
            192.0D,
            1.0D,
            " blocks"
    ).configKey("render.chams.distance").advancedOnly());
    public final ModeSetting mode = register(new ModeSetting(
            "Mode",
            MODE_EXTERNAL,
            MODE_EXTERNAL,
            MODE_INTERNAL,
            MODE_BOTH
    ).configKey("render.chams.mode").advancedOnly());
    public final BooleanSetting originalTexture = register(new BooleanSetting(
            "Original Texture",
            false
    ).configKey("render.chams.originalTexture").advancedOnly());
    public final ModeSetting shader = register(new ModeSetting(
            "Shader",
            SHADER_PLASMA,
            SHADER_PLASMA,
            SHADER_NEBULA
    ).configKey("render.chams.shader").visibleWhen(() -> hasShaderFill()).advancedOnly());
    public final NumberSetting shaderSpeed = register(new NumberSetting(
            "Shader Speed",
            4.0D,
            0.1D,
            8.0D,
            0.1D,
            "x"
    ).configKey("render.chams.shader.speed").visibleWhen(() -> hasShaderFill()).advancedOnly());
    public final NumberSetting opacity = register(new NumberSetting(
            "Opacity",
            1.0D,
            0.0D,
            1.0D,
            0.01D,
            ""
    ).configKey("render.chams.opacity").advancedOnly());
    public final NumberSetting outlineThickness = register(new NumberSetting(
            "Outline Thickness",
            1.5D,
            0.5D,
            5.0D,
            0.1D,
            "px"
    ).configKey("render.chams.thickness").visibleWhen(() -> hasOutline()).advancedOnly());
    public final NumberSetting glowRadius = register(new NumberSetting(
            "Glow Radius",
            1.3D,
            0.5D,
            8.0D,
            0.1D,
            "x"
    ).configKey("render.chams.glowRadius").visibleWhen(() -> hasGlow()).advancedOnly());
    public final NumberSetting glowStrength = register(new NumberSetting(
            "Glow Strength",
            0.33D,
            0.0D,
            3.0D,
            0.01D,
            "x"
    ).configKey("render.chams.glowStrength").visibleWhen(() -> hasGlow()).advancedOnly());
    public final BooleanSetting additiveBlending = register(new BooleanSetting(
            "Additive Glow",
            true
    ).configKey("render.chams.blending").visibleWhen(() -> hasGlow()).advancedOnly());
    public final NumberSetting glassBlur = register(new NumberSetting(
            "Glass Blur",
            0.0D,
            0.0D,
            60.0D,
            1.0D,
            "px"
    ).configKey("render.chams.glassBlur").visibleWhen(() -> hasGlass()).advancedOnly());
    public final BooleanSetting mirror = register(new BooleanSetting(
            "Mirror",
            true
    ).configKey("render.chams.mirror").visibleWhen(() -> hasGlass()).advancedOnly());

    public ChamsFeature() {
        super("Chams", "Replace selected entity models with shader silhouettes", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public static ChamsFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(ChamsFeature.class);
    }

    public boolean shouldRender(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || entity == null
                || entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()
                || entity.isRemoved()
                || !entity.isAlive()
                || minecraft.player.distanceToSqr(entity) > this.distance.getValue() * this.distance.getValue()) {
            return false;
        }
        if (entity instanceof Player) {
            return this.targets.isSelected(TARGET_PLAYERS);
        }
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return this.targets.isSelected(TARGET_ITEMS);
        }
        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MONSTER) {
            return this.targets.isSelected(TARGET_HOSTILE);
        }
        return this.targets.isSelected(TARGET_PASSIVE);
    }

    public int resolvedColor() {
        return ColorMode.resolve(this.colorMode, this.color);
    }

    public boolean hasShaderFill() {
        return this.effects.isSelected(EFFECT_SHADER_FILL);
    }

    public boolean hasSolid() {
        return this.effects.isSelected(EFFECT_SOLID);
    }

    public boolean hasGlass() {
        return this.effects.isSelected(EFFECT_GLASS);
    }

    public boolean hasOutline() {
        return this.effects.isSelected(EFFECT_OUTLINE);
    }

    public boolean hasGlow() {
        return this.effects.isSelected(EFFECT_GLOW) && usesExternal();
    }

    public boolean keepsOriginalModel() {
        return this.originalTexture.getValue();
    }

    public boolean usesInternal() {
        return this.mode.is(MODE_INTERNAL) || this.mode.is(MODE_BOTH);
    }

    public boolean usesExternal() {
        return this.mode.is(MODE_EXTERNAL) || this.mode.is(MODE_BOTH);
    }

    public boolean hasAnyVisual() {
        return hasShaderFill()
                || hasSolid()
                || hasGlass()
                || hasOutline()
                || hasGlow()
                || usesInternal();
    }
}
