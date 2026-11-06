package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * CrystalTap — кристалл-тап под курсором. Если смотришь на обсидиан без кристалла — ставит
 * кристалл на него (опция), а кристалл под курсором бьёт. Кулдауны на ставку и на атаку
 * настраиваются отдельно (в тиках).
 */
public final class CrystalTapFeature extends Feature {
    private final BooleanSetting autoAddCrystal = register(new BooleanSetting("Автоматически ставить кристалл", true));
    private final NumberSetting addCooldown = register(new NumberSetting("Задержка на ставку", 0.0, 0.0, 5.0, 1.0, " ticks"));
    private final NumberSetting attackCooldown = register(new NumberSetting("Задержка на атаку", 0.0, 0.0, 5.0, 1.0, " ticks"));

    private int addCrystalCooldown;
    private int attackCrystalCooldown;

    public CrystalTapFeature() {
        super("CrystalTap", "Ставит и бьёт кристалл под курсором", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

        if (this.autoAddCrystal.getValue()) {
            this.addCrystal(mc, player);
        }

        // Атака кристалла под курсором
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof EndCrystal) {
            if (this.attackCrystalCooldown > 0) {
                this.attackCrystalCooldown--;
                return;
            }
            mc.gameMode.attack(player, entityHit.getEntity());
            player.swing(InteractionHand.MAIN_HAND);
            this.attackCrystalCooldown = this.attackCooldown.getValue().intValue();
        }
    }

    private void addCrystal(Minecraft mc, LocalPlayer player) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        BlockPos obsidian = blockHit.getBlockPos();
        var state = mc.level.getBlockState(obsidian);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.CRYING_OBSIDIAN)) {
            return;
        }
        // На этом обсидиане уже стоит кристалл — второй не ставим
        if (crystalOn(mc, obsidian)) {
            return;
        }
        // Ставим только если в руке кристалл
        if (!player.getMainHandItem().is(Items.END_CRYSTAL)) {
            return;
        }
        if (this.addCrystalCooldown > 0) {
            this.addCrystalCooldown--;
            return;
        }
        BlockHitResult placeHit = new BlockHitResult(blockHit.getLocation(), blockHit.getDirection(), obsidian, true);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placeHit);
        this.addCrystalCooldown = this.addCooldown.getValue().intValue();
    }

    private boolean crystalOn(Minecraft mc, BlockPos obsidian) {
        BlockPos crystalPos = obsidian.above();
        AABB box = new AABB(obsidian).inflate(1.0D);
        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, box)) {
            if (crystal.blockPosition().equals(crystalPos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDisable() {
        this.addCrystalCooldown = 0;
        this.attackCrystalCooldown = 0;
    }
}