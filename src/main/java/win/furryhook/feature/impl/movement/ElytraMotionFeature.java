package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.setting.BindSetting;

/**
 * ElytraMotion — остановка перед целью на элитрах (порт из Javelin).
 * 
 * Логика:
 * - Если цель есть, игрок планирует, и дистанция < 2.4 блоков → останавливаем полностью (setNoGravity)
 * - Если цели нет → включаем гравитацию обратно
 */
public final class ElytraMotionFeature extends Feature {

    private boolean waitTarget;

    public ElytraMotionFeature() {
        super("ElytraMotion", "Остановка перед целью на элитрах", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!event.isPre()) {
            return;
        }

        var player = event.getPlayer();
        if (player == null) {
            return;
        }

        AuraFeature aura = AuraFeature.getEnabled();
        LivingEntity target = (aura != null) ? aura.getTarget() : null;

        if (target == null) {
            if (!this.waitTarget) {
                player.setNoGravity(false);
                this.waitTarget = true;
            }
        } else {
            this.waitTarget = false;

            // Проверяем: планируем ли мы, дистанция < 2.4 блока, цель не планирует
            if (player.isFallFlying()) {
                // Вычисляем центр хитбокса цели с предсказанием движения (target.getY() - target.yOld) * 2
                Vec3 targetCenter = target.getBoundingBox().getCenter()
                        .add(0.0D, (target.getY() - target.yOld) * 2.0D, 0.0D);
                
                double distance = player.getEyePosition(1.0F).distanceTo(targetCenter);

                if (distance < 2.4000000953674316D && !target.isFallFlying()) {
                    // Останавливаем игрока полностью
                    player.setDeltaMovement(0.0D, 0.0D, 0.0D);
                    player.setNoGravity(true);
                } else {
                    player.setNoGravity(false);
                }
            } else {
                player.setNoGravity(false);
            }
        }
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            this.waitTarget = false;
            mc.player.setNoGravity(false);
        }
    }
}
