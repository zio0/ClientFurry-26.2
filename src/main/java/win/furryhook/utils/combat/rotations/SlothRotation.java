package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.combat.AuraRaycast;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sloth/ToolRise rotation — портирована из Javelin.
 * 
 * Особенности:
 * - Продвинутый джиттер с осцилляцией (±0.2)
 * - Адаптивная скорость на основе дельты yaw игрока
 * - Учёт водного спринта (снижение скорости pitch)
 * - Замораживание ротации при вертикальном взгляде или коллизии
 * - Плавное ускорение через smooth фактор
 * - Рандомизация ±2 градуса для обхода античита
 * - GCD квантизация для реалистичного движения мыши
 */
public final class SlothRotation implements AuraRotation {
    
    // Константы осциллятора джиттера
    private static final float JITTER_MIN = -0.2F;
    private static final float JITTER_MAX = 0.2F;
    private static final float JITTER_STEP = 0.02F;
    
    // Мультипликатор для адаптивного джиттера на основе движения головы
    private static final float HEAD_MOVEMENT_MULT = 3.0F;
    
    // Рандомизация для обхода античита
    private static final float RANDOM_OFFSET = 2.0F;
    
    // Безопасная дистанция для raymiss проверки
    private static final double REACH_MARGIN = 0.15D;
    
    private double reach = 3.0D;
    private boolean throughWalls;
    private boolean waterSprintActive;
    
    // Состояние осциллятора джиттера
    private float jitterOffset = 0.0F;
    private boolean jitterIncreasing = false;
    
    // GCD параметры (default mouse sensitivity 100% = 0.5)
    private static final float GCD_STEP = 0.15F;
    
    // Последний известный таргет
    private int lastTargetId = Integer.MIN_VALUE;
    
    /** Устанавливает дистанцию атаки для проверок достижимости */
    public void setReach(double reach) {
        this.reach = reach;
    }
    
    /** Включает/выключает атаку через стены */
    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
    
    /** 
     * Активирует режим водного спринта - снижает скорость pitch ротации.
     * Вызывается из AuraFeature когда игрок атакует в воде во время спринта.
     */
    public void setWaterSprintActive(boolean active) {
        this.waterSprintActive = active;
    }
    
    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (player == null || target == null || !target.isAlive()) {
            reset();
            return;
        }
        
        // Смена таргета - сброс джиттера
        if (this.lastTargetId != target.getId()) {
            this.lastTargetId = target.getId();
            this.jitterOffset = 0.0F;
            this.jitterIncreasing = false;
        }
        
        // Обновляем осциллятор джиттера
        updateJitterOscillator();
        
        // Текущая ротация
        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();
        
        // Вычисляем движение головы игрока (для адаптивного джиттера)
        float headMovement = Mth.wrapDegrees(player.getYRot() - player.yRotO);
        
        // Целимся в центр хитбокса с небольшим смещением вверх
        Vec3 aimPoint = getAimPoint(target);
        
        // Вычисляем идеальную ротацию
        float[] ideal = rotationTo(player.getEyePosition(1.0F), aimPoint);
        
        // Применяем джиттер на основе движения головы
        float adaptiveJitter = this.jitterOffset * Math.abs(headMovement) * HEAD_MOVEMENT_MULT;
        ideal[0] += adaptiveJitter;
        
        // Применяем рандомизацию ±2 градуса для обхода античита
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ideal[0] += random.nextFloat(-RANDOM_OFFSET, RANDOM_OFFSET);
        ideal[1] += random.nextFloat(-RANDOM_OFFSET, RANDOM_OFFSET);
        
        // Вычисляем дельты
        float deltaYaw = Mth.wrapDegrees(ideal[0] - currentYaw);
        float deltaPitch = ideal[1] - currentPitch;
        
        // Smooth фактор на основе джиттера (0..1)
        float smooth = Math.clamp(Math.abs(this.jitterOffset), 0.0F, 1.0F);
        
        // Вычисляем абсолютные значения для speed calc
        float absYaw = Math.abs(deltaYaw);
        float absPitch = Math.abs(deltaPitch);
        
        // Проверки на заморозку ротации
        boolean verticalFreeze = (currentPitch >= 89.9F || currentPitch <= -89.9F);
        boolean collisionFreeze = player.getBoundingBox().intersects(target.getBoundingBox()) 
                                  && player.horizontalCollision;
        boolean shouldFreeze = verticalFreeze || collisionFreeze;
        
        // Скорость yaw - адаптивная, с заморозкой при необходимости
        float speedYaw = shouldFreeze ? 0.0F : Mth.clamp(absYaw * smooth, 0.0F, 999.0F);
        
        // Скорость pitch - с учетом водного спринта, половинная скорость
        float speedPitch = Mth.clamp(
            absPitch * (this.waterSprintActive ? 0.0F : smooth / 2.0F),
            0.0F,
            999.0F
        );
        
        // Применяем ограничения
        float yawStep = Mth.clamp(deltaYaw, -speedYaw, speedYaw);
        float pitchStep = Mth.clamp(deltaPitch, -speedPitch, speedPitch);
        
        // Новые углы
        float newYaw = currentYaw + yawStep;
        float newPitch = currentPitch + pitchStep;
        
        // GCD квантизация для реалистичного движения мыши
        newYaw = applyGCD(newYaw, currentYaw);
        newPitch = applyGCD(newPitch, currentPitch);
        
        // Применяем ротацию
        RotationContext.setRotation(
            newYaw,
            Mth.clamp(newPitch, -90.0F, 90.0F)
        );
    }
    
    @Override
    public void reset() {
        this.lastTargetId = Integer.MIN_VALUE;
        this.jitterOffset = 0.0F;
        this.jitterIncreasing = false;
        this.waterSprintActive = false;
    }
    
    /**
     * Обновляет осциллятор джиттера.
     * Создает плавное колебание между -0.2 и +0.2 с шагом 0.02
     */
    private void updateJitterOscillator() {
        if (!this.jitterIncreasing) {
            this.jitterOffset += JITTER_STEP;
            if (this.jitterOffset >= JITTER_MAX) {
                this.jitterOffset = JITTER_MAX;
                this.jitterIncreasing = true;
            }
        } else {
            this.jitterOffset -= JITTER_STEP;
            if (this.jitterOffset <= JITTER_MIN) {
                this.jitterOffset = JITTER_MIN;
                this.jitterIncreasing = false;
            }
        }
    }
    
    /**
     * Получает точку прицеливания - центр хитбокса со смещением вверх на 0.5
     */
    private Vec3 getAimPoint(LivingEntity target) {
        AABB box = target.getBoundingBox();
        return box.getCenter().add(0, 0.5F, 0);
    }
    
    /**
     * Вычисляет yaw и pitch от eye до точки прицеливания
     */
    private static float[] rotationTo(Vec3 eye, Vec3 point) {
        Vec3 diff = point.subtract(eye);
        double horizontal = Math.hypot(diff.x, diff.z);
        
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float pitch = Mth.clamp(
            (float) -Math.toDegrees(Math.atan2(diff.y, horizontal)),
            -90.0F,
            90.0F
        );
        
        return new float[]{yaw, pitch};
    }
    
    /**
     * GCD grid snapping - квантизация углов для симуляции реального движения мыши.
     * Основано на чувствительности мыши (по умолчанию 100% = 0.5 в опциях).
     * GCD шаг примерно 0.15 градуса на единицу чувствительности.
     */
    private float applyGCD(float newAngle, float currentAngle) {
        float delta = newAngle - currentAngle;
        float quantized = Math.round(delta / GCD_STEP) * GCD_STEP;
        return currentAngle + quantized;
    }
    
    /**
     * Проверяет, попадает ли луч с данной ротацией в таргет.
     * Используется для валидации джиттера.
     */
    private boolean hitsTarget(LocalPlayer player, LivingEntity target, float yaw, float pitch) {
        return AuraRaycast.rotationIntersectsTarget(
            player,
            target,
            yaw,
            pitch,
            this.reach,
            0.0D,
            REACH_MARGIN,
            this.throughWalls
        );
    }
}
