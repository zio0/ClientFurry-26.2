package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;

public final class NoPushFeature extends Feature {
    public final BooleanSetting entity = register(new BooleanSetting("Entity", true));
    public final BooleanSetting blocks = register(new BooleanSetting("Blocks", true));
    public final BooleanSetting water = register(new BooleanSetting("Water", true));
    public final BooleanSetting fishingHook = register(new BooleanSetting("Fishing Hook", true));

    public NoPushFeature() {
        super("NoPush", "Prevents the player from being pushed", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static NoPushFeature getInstance() {
        return FeatureManager.INSTANCE.getFeature(NoPushFeature.class);
    }

    public static NoPushFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(NoPushFeature.class);
    }

    public static boolean shouldCancelEntityPush(Entity self, Entity other) {
        NoPushFeature feature = getEnabled();
        if (feature == null || !feature.entity.getValue()) {
            return false;
        }

        Player player = localPlayer();
        return player != null && self == player && other != player;
    }

    public static boolean shouldCancelBlockPush(Player player, BlockState state) {
        NoPushFeature feature = getEnabled();
        if (feature == null || !feature.blocks.getValue()) {
            return false;
        }

        if (state != null && state.is(Blocks.COBWEB)) {
            return false;
        }

        Player local = localPlayer();
        return local != null && player == local && state != null && !state.isAir();
    }

    public static boolean shouldCancelClosestSpacePush(LocalPlayer player) {
        NoPushFeature feature = getEnabled();
        if (feature == null || !feature.blocks.getValue()) {
            return false;
        }

        Player local = localPlayer();
        return local != null && player == local;
    }

    public static boolean shouldCancelFluidPush(Player player) {
        NoPushFeature feature = getEnabled();
        if (feature == null || !feature.water.getValue()) {
            return false;
        }

        Player local = localPlayer();
        return local != null && player == local;
    }

    public static boolean shouldCancelFishingHookPull(FishingHook hook, Entity target) {
        NoPushFeature feature = getEnabled();
        if (feature == null || !feature.fishingHook.getValue()) {
            return false;
        }

        Player local = localPlayer();
        return local != null && target == local && hook != null && hook.getOwner() != local;
    }

    private static Player localPlayer() {
        return Minecraft.getInstance().player;
    }
}
