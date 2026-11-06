package win.furryhook.utils.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Порт {@code tech.javelin.base.util.math.PredictUtils} - {@code predictElytra} и
 * {@code realPredict}, один в один.
 *
 * <p>Это не симуляция элитра-физики. Javelin берёт скорость цели как дельту позиции за прошлый
 * тик и <b>поворачивает этот вектор на её же угловую дельту</b> ({@code yaw - lastYaw},
 * {@code pitch - lastPitch}) на каждом шаге вперёд. Получается не прямая, а дуга: игрок,
 * закладывающий вираж, экстраполируется по своему виражу, а не по касательной. Именно это и
 * делает точку упреждения пригодной для боя на элитрах, где цель за тик проходит два блока и
 * почти всегда доворачивает.
 *
 * <p>Количество тиков упреждения само зависит от дистанции - {@code realPredict} считает предикт
 * дважды: первый прогон на базовых тиках даёт грубую точку, дистанция до неё делится на 100 и
 * становится множителем {@code minusTicks} для второго, настоящего прогона.
 *
 * <p><b>Отличие от {@link PredictUtils}:</b> тот - порт другого, физического предиктора javelin
 * (один шаг {@code updateFallFlyingMovement}). Элитра-таргет пользуется этим.
 */
public final class JavelinPredict {

    /**
     * {@code Type.LIMIT} - режим "Легитный": количество тиков упреждения зажимается в 2.7,
     * то есть точка никогда не уезжает дальше, чем на три тика движения цели.
     */
    public enum Type {
        DEFAULT,
        LIMIT
    }

    /**
     * Базовая добавка к тикам упреждения. В javelin это {@code resolvedPing / 15} при включённом
     * FastLatency и жёсткая тройка иначе; в этой базе модуля-аналога FastLatency нет, поэтому
     * всегда работает вторая ветка - ровно как у javelin с выключенным FastLatency.
     */
    private static final float BASE_TICKS = 3.0F;

    private JavelinPredict() {
    }

    /**
     * @param minusTicks множитель тиков упреждения на блок дистанции до центра хитбокса цели
     * @param type       {@code LIMIT} зажимает итоговые тики в 2.7
     * @return предсказанная позиция цели (уровень ног, как и в оригинале), либо её текущая
     *         позиция, если цель не игрок
     */
    public static Vec3 predictElytra(LivingEntity entity, float minusTicks, Type type) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (!(entity instanceof Player player) || local == null) {
            return entity.position();
        }

        Vec3 basePos = player.getPosition(1.0F);
        Vec3 velocity = new Vec3(
                player.getX() - player.xOld,
                player.getY() - player.yOld,
                player.getZ() - player.zOld
        );

        float ticks = (float) (local.getEyePosition().distanceTo(entity.getBoundingBox().getCenter()) * minusTicks);
        ticks += BASE_TICKS;

        if (type == Type.LIMIT) {
            ticks = Mth.clamp(ticks, 0.0F, 2.7F);
        }

        float deltaYaw = Mth.wrapDegrees(player.getYRot() - player.yRotO);
        float deltaPitch = Mth.wrapDegrees(player.getXRot() - player.xRotO);

        Vec3 predictedPos = basePos;
        int intTicks = (int) ticks;
        float frac = ticks - intTicks;

        for (int i = 0; i < intTicks; i++) {
            velocity = turn(velocity, deltaYaw, deltaPitch);
            predictedPos = predictedPos.add(velocity);
        }

        if (frac > 0.0F) {
            velocity = turn(velocity, deltaYaw * frac, deltaPitch * frac);
            predictedPos = predictedPos.add(velocity.scale(frac));
        }

        return predictedPos;
    }

    /**
     * Один шаг доворота вектора скорости на угловую дельту цели, с сохранением длины.
     * Ниже порога 0.001 блока за тик javelin вектор не трогает вовсе.
     */
    private static Vec3 turn(Vec3 velocity, float deltaYaw, float deltaPitch) {
        double speed = velocity.length();
        if (speed <= 0.001D) {
            return velocity;
        }

        double velYaw = Math.atan2(-velocity.x, velocity.z) + Math.toRadians(deltaYaw);
        double velPitch = -Math.atan2(velocity.y, Math.hypot(velocity.x, velocity.z)) + Math.toRadians(deltaPitch);
        velPitch = Mth.clamp(velPitch, -Math.PI / 2.0D, Math.PI / 2.0D);

        return new Vec3(
                -Math.sin(velYaw) * Math.cos(velPitch) * speed,
                -Math.sin(velPitch) * speed,
                Math.cos(velYaw) * Math.cos(velPitch) * speed
        );
    }

    /**
     * Двухпроходный предикт javelin: грубая точка на базовых тиках, затем настоящая - с
     * множителем тиков, взятым из дистанции до этой грубой точки, делённой на 100 и зажатой в 12.
     */
    public static Vec3 realPredict(LivingEntity entity, Type type) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local == null) {
            return entity.position();
        }

        Vec3 predict = predictElytra(entity, 0.0F, type);
        float minusTicks = Mth.clamp((float) local.getEyePosition().distanceTo(predict) / 100.0F, 0.0F, 12.0F);
        minusTicks = Math.max(minusTicks, 0.0F);
        return predictElytra(entity, minusTicks, type);
    }
}
