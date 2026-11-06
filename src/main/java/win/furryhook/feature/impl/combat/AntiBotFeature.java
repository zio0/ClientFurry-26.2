package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Ported from nuclear's AntiBot module: flags players whose gear/hunger looks
 * like a scripted anti-cheat bot rather than a real player, so {@link AuraFeature}
 * can skip attacking them.
 */
public final class AntiBotFeature extends Feature {
    private static final Set<Player> FLAGGED_BOTS = Collections.newSetFromMap(new WeakHashMap<>());

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    public final ModeSetting mode = register(new ModeSetting(
            "Mode", "ReallyWorld", "ReallyWorld", "LonyGrief"
    ));

    public AntiBotFeature() {
        super("AntiBot", "Flags scripted anti-cheat bots so Aura skips attacking them", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    public static AntiBotFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(AntiBotFeature.class);
    }

    public static boolean checkBot(LivingEntity entity) {
        AntiBotFeature feature = getEnabled();
        return feature != null && entity instanceof Player player && FLAGGED_BOTS.contains(player);
    }

    @Override
    protected void onDisable() {
        FLAGGED_BOTS.clear();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer self = mc.player;
        if (self == null || mc.level == null) return;

        for (Player entity : mc.level.players()) {
            if (entity == self) continue;

            boolean isBot = mode.is("LonyGrief") ? checkLonyGrief(entity) : checkReallyWorld(entity);

            if (isBot) {
                FLAGGED_BOTS.add(entity);
            } else {
                FLAGGED_BOTS.remove(entity);
            }
        }
    }

    private boolean checkReallyWorld(Player entity) {
        if (entity.getFoodData().getFoodLevel() != 20) return false;
        if (!entity.getOffhandItem().isEmpty()) return false;

        boolean anyPiece = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = entity.getItemBySlot(slot);
            if (armor.isEmpty() || !armor.isEnchantable() || armor.isDamaged()) return false;
            if (isCommonArmorPiece(armor)) anyPiece = true;
        }
        if (!anyPiece) return false;

        return isCommonHeldItem(entity.getMainHandItem());
    }

    private boolean checkLonyGrief(Player entity) {
        ItemStack mainhand = entity.getMainHandItem();
        ItemStack offhand = entity.getOffhandItem();

        if (mainhand.is(Items.TOTEM_OF_UNDYING) && offhand.is(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        int matchCount = 0;
        if (isSuspiciousHeldItem(mainhand)) matchCount++;
        if (isSuspiciousHeldItem(offhand)) matchCount++;

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = entity.getItemBySlot(slot);
            if (!armor.isEmpty() && isSuspiciousArmorPiece(armor)) {
                matchCount++;
            }
        }

        return matchCount >= 2 && entity.getFoodData().getFoodLevel() == 20;
    }

    private boolean isSuspiciousHeldItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        boolean plausibleItem = stack.is(Items.WOODEN_SWORD) || stack.is(Items.NETHERITE_SWORD)
                || stack.is(Items.IRON_SWORD) || stack.is(Items.DIAMOND_SWORD)
                || stack.is(Items.CROSSBOW) || stack.is(Items.BOW)
                || stack.is(Items.NETHERITE_AXE) || stack.is(Items.DIAMOND_AXE) || stack.is(Items.STONE_AXE)
                || stack.is(Items.END_CRYSTAL) || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.FIREWORK_ROCKET)
                || stack.is(Items.ELYTRA) || stack.is(Items.TRIDENT);
        return plausibleItem && !stack.isDamaged() && stack.getEnchantments().isEmpty() && stack.getCount() == 1;
    }

    private boolean isSuspiciousArmorPiece(ItemStack stack) {
        boolean plausiblePiece = stack.is(Items.NETHERITE_HELMET) || stack.is(Items.DIAMOND_HELMET)
                || stack.is(Items.ELYTRA) || stack.is(Items.DIAMOND_CHESTPLATE)
                || stack.is(Items.NETHERITE_CHESTPLATE) || stack.is(Items.NETHERITE_LEGGINGS)
                || stack.is(Items.DIAMOND_LEGGINGS) || stack.is(Items.NETHERITE_BOOTS) || stack.is(Items.DIAMOND_BOOTS);
        return plausiblePiece && !stack.isDamaged() && stack.getEnchantments().isEmpty() && stack.getCount() == 1;
    }

    private boolean isCommonArmorPiece(ItemStack stack) {
        return stack.is(Items.LEATHER_BOOTS) || stack.is(Items.LEATHER_LEGGINGS)
                || stack.is(Items.LEATHER_CHESTPLATE) || stack.is(Items.LEATHER_HELMET)
                || stack.is(Items.IRON_BOOTS) || stack.is(Items.IRON_LEGGINGS)
                || stack.is(Items.IRON_CHESTPLATE) || stack.is(Items.IRON_HELMET)
                || stack.is(Items.DIAMOND_BOOTS) || stack.is(Items.DIAMOND_LEGGINGS)
                || stack.is(Items.DIAMOND_CHESTPLATE) || stack.is(Items.DIAMOND_HELMET);
    }

    private boolean isCommonHeldItem(ItemStack stack) {
        return stack.isEmpty()
                || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.IRON_SWORD)
                || stack.is(Items.FISHING_ROD)
                || stack.is(Items.IRON_AXE) || stack.is(Items.DIAMOND_AXE)
                || stack.is(Items.IRON_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE)
                || stack.is(Items.TORCH);
    }
}
