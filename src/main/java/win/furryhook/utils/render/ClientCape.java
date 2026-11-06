package win.furryhook.utils.render;

import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import win.furryhook.context.MinecraftContext;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.CapeFeature;

import java.util.UUID;

public final class ClientCape {

    private ClientCape() {
    }

    public static boolean shouldForceCape(UUID playerUuid) {
        if (MinecraftContext.mc.player == null) {
            return false;
        }

        CapeFeature feature = getFeature();
        if (feature == null || !feature.isEnabled()) {
            return false;
        }

        return MinecraftContext.mc.player.getUUID().equals(playerUuid);
    }

    public static PlayerSkin apply(PlayerSkin skin) {
        CapeFeature feature = getFeature();
        String fileName = (feature != null) ? feature.currentStyle().getFileName() : "pmcboykisser.png";

        Identifier dynamicTextureId = Identifier.fromNamespaceAndPath("furryhook", "textures/cape/" + fileName);

        ClientAsset.Texture dynamicTexture = new ClientAsset.Texture() {
            @Override
            public Identifier id() {
                return dynamicTextureId;
            }

            @Override
            public Identifier texturePath() {
                return dynamicTextureId;
            }
        };

        return new PlayerSkin(skin.body(), dynamicTexture, skin.elytra(), skin.model(), skin.secure());
    }

    private static CapeFeature getFeature() {
        return FeatureManager.INSTANCE.getFeature(CapeFeature.class);
    }
}
