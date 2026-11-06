package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * ElytraResolver — port of PounchClient's {@code lightning.product.ElytraResolver}.
 *
 * <p>A two-state approach/retreat machine for elytra duels: it aims at the target's predicted
 * position while closing in, and the moment the fight gets close (or the target turns its face at
 * us) it flips to a retreat point offset sideways/up from the target, so the aura flies through and
 * out instead of parking in front of a sword.
 *
 * <p>Both the aim point and the retreat vector are built from the target's own position delta, not
 * from its render position — same as the source. Like the source, enabling this forces the aura's
 * rotation mode to {@code Grim} and restores the previous mode on disable.
 */
public final class ElytraResolverFeature extends Feature {

    /** Retreat times out after this even if the retreat distance is never reached. */
    private static final long RETREAT_TIMEOUT_MS = 2000L;
    /** Predicted position = position + positionDelta * this. */
    private static final double PREDICT_TICKS = 3.0D;
    /** The target only counts as "facing us" inside this distance. */
    private static final double FACING_MAX_DISTANCE = 4.0D;
    private static final double FACING_HARD_DISTANCE = 3.5D;
    /** dot(targetLook, target to us) above which the target is looking at us. */
    private static final double FACING_DOT = 0.5D;
    /** Degenerate "we are inside the target" guard for the retreat direction. */
    private static final double MIN_AWAY_LENGTH = 0.1D;

    private static final String MODE_SIDE = "В сторону";
    private static final String MODE_UP = "Вверх";
    private static final String MODE_DIAGONAL = "Диагональ";

    private static final String GRIM_ROTATION = "Grim";

    private enum Phase {
        APPROACH,
        RETREAT
    }

    public final NumberSetting attackDistance = register(new NumberSetting(
            "Дистанция атаки", 3.0D, 2.0D, 4.5D, 0.1D, " blocks"
    ));

    public final NumberSetting retreatDistance = register(new NumberSetting(
            "Дистанция отлёта", 8.0D, 5.0D, 12.0D, 0.5D, " blocks"
    ));

    public final NumberSetting retreatAngle = register(new NumberSetting(
            "Угол отлёта", 50.0D, 20.0D, 90.0D, 5.0D, "°"
    ));

    public final ModeSetting retreatMode = register(new ModeSetting(
            "Режим отлёта", MODE_SIDE, MODE_SIDE, MODE_UP, MODE_DIAGONAL
    ));

    public final BooleanSetting alternateSides = register(new BooleanSetting(
            "Чередовать стороны", true
    ));

    private Phase phase = Phase.APPROACH;
    private LivingEntity target;
    private Vec3 aimPoint;
    private Vec3 retreatDirection;
    private boolean flipSide;
    private long phaseStart;
    private String savedRotationMode;

    public ElytraResolverFeature() {
        super("ElytraResolver", "Подлёт и отлёт от цели на элитре", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    public static ElytraResolverFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(ElytraResolverFeature.class);
    }

    @Override
    protected void onEnable() {
        reset();
        this.phaseStart = 0L;

        AuraFeature aura = AuraFeature.getMarkerFeature();
        if (aura != null) {
            this.savedRotationMode = aura.componentMode.getValue();
            aura.componentMode.setValue(GRIM_ROTATION);
        }
    }

    @Override
    protected void onDisable() {
        reset();

        if (this.savedRotationMode != null) {
            AuraFeature aura = AuraFeature.getMarkerFeature();
            if (aura != null && aura.componentMode.containsMode(this.savedRotationMode)) {
                aura.componentMode.setValue(this.savedRotationMode);
            }
            this.savedRotationMode = null;
        }
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!event.isPre()) {
            return;
        }
        LocalPlayer player = event.getPlayer();
        AuraFeature aura = AuraFeature.getEnabled();
        LivingEntity auraTarget = aura == null ? null : aura.getTarget();

        if (player == null || auraTarget == null || !player.isFallFlying()) {
            reset();
            return;
        }

        double distance = player.getEyePosition().distanceTo(auraTarget.position());
        update(player, auraTarget, distance);
    }

    /** {@code ElytraResolver.n_1700_B(target, distanceToTarget)}. */
    private void update(LocalPlayer player, LivingEntity target, double distance) {
        this.target = target;

        if (isTargetFacingUs(player, target, distance) && this.phase == Phase.APPROACH) {
            enterRetreat();
        }

        switch (this.phase) {
            case APPROACH -> {
                if (distance <= attackDistance.getValue()) {
                    enterRetreat();
                }
            }
            case RETREAT -> {
                if (distance >= retreatDistance.getValue()) {
                    this.phase = Phase.APPROACH;
                    this.retreatDirection = null;
                    this.phaseStart = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - this.phaseStart > RETREAT_TIMEOUT_MS) {
                    this.phase = Phase.APPROACH;
                    this.retreatDirection = null;
                }
            }
        }

        updateAimPoint(player, target);
    }

    /**
     * {@code ElytraResolver.J_1907_R(target, distance)} — the target is close and looking straight
     * at us, i.e. about to swing. Bail out before the trade.
     */
    private boolean isTargetFacingUs(LocalPlayer player, LivingEntity target, double distance) {
        if (distance > FACING_MAX_DISTANCE) {
            return false;
        }
        Vec3 toPlayer = player.position().subtract(target.position());
        if (toPlayer.lengthSqr() < 1.0E-6D) {
            return false;
        }
        double dot = target.getLookAngle().dot(toPlayer.normalize());
        return dot > FACING_DOT && distance < FACING_HARD_DISTANCE;
    }

    /** {@code ElytraResolver.R_4764_Y(target)}. */
    private void updateAimPoint(LocalPlayer player, LivingEntity target) {
        Vec3 predicted = predictedPosition(target);

        if (this.phase == Phase.APPROACH) {
            this.aimPoint = predicted.add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
            return;
        }

        if (this.retreatDirection == null) {
            this.retreatDirection = buildRetreatDirection(player, predicted);
        }
        double height = switch (retreatMode.getValue()) {
            case MODE_UP -> 6.0D;
            case MODE_DIAGONAL -> 3.0D;
            default -> 1.5D;
        };
        this.aimPoint = predicted
                .add(this.retreatDirection.scale(retreatDistance.getValue()))
                .add(0.0D, height, 0.0D);
    }

    /** {@code ElytraResolver.G_564_y(target)} — position + last tick's delta, three ticks out. */
    private static Vec3 predictedPosition(LivingEntity target) {
        Vec3 position = target.position();
        Vec3 delta = position.subtract(new Vec3(target.xOld, target.yOld, target.zOld));
        return position.add(delta.scale(PREDICT_TICKS));
    }

    /**
     * {@code ElytraResolver.n_1700_B(targetPos, playerPos)} — the vector away from the target,
     * rotated around Y by {@code Угол отлёта} (sign alternating when the setting is on), with the
     * per-mode vertical component mixed in, normalised. Computed once per retreat.
     */
    private Vec3 buildRetreatDirection(LocalPlayer player, Vec3 targetPos) {
        Vec3 away = player.position().subtract(targetPos);
        away = away.length() < MIN_AWAY_LENGTH
                ? player.getLookAngle().scale(-1.0D)
                : away.normalize();

        double angle = retreatAngle.getValue();
        if (alternateSides.getValue()) {
            angle = this.flipSide ? angle : -angle;
            this.flipSide = !this.flipSide;
        }

        double rad = Math.toRadians(angle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double newX = away.x * cos - away.z * sin;
        double newZ = away.x * sin + away.z * cos;
        double y = switch (retreatMode.getValue()) {
            case MODE_UP -> 0.6D;
            case MODE_DIAGONAL -> 0.3D;
            default -> 0.1D;
        };
        return new Vec3(newX, y, newZ).normalize();
    }

    /** {@code ElytraResolver.P_1922_E(target)}. */
    private void enterRetreat() {
        this.phase = Phase.RETREAT;
        this.retreatDirection = null;
        this.phaseStart = System.currentTimeMillis();
    }

    /** Called by the aura when a hit lands, so a connected swing immediately breaks away. */
    public void onHit(LivingEntity hitTarget) {
        if (hitTarget != null && hitTarget == this.target && this.phase == Phase.APPROACH) {
            enterRetreat();
        }
    }

    private void reset() {
        this.phase = Phase.APPROACH;
        this.target = null;
        this.aimPoint = null;
        this.retreatDirection = null;
    }

    public boolean isApproaching() {
        return this.phase == Phase.APPROACH;
    }

    public boolean isRetreating() {
        return this.phase == Phase.RETREAT;
    }

    public LivingEntity getResolvedTarget() {
        return this.target;
    }

    /** Absolute aim point for the current phase, or {@code null} while the resolver is idle. */
    public Vec3 getAimPoint() {
        return this.aimPoint;
    }

    /** True while the resolver is actively driving the aim at {@code target}. */
    /**
     * Stands down whenever ElytraTarget is on.
     *
     * <p>Both modules answer "where does the head point while gliding", and the aura asks this one
     * first. So with both enabled the resolver won the aim every time — and its retreat leg aims at
     * a point deliberately offset to the side of and above the target, which is a ray that enters
     * nothing. From the outside that is a target locked on the HUD and not one swing landing, and
     * nothing done to ElytraTarget could change it, because ElytraTarget's aim never reached the
     * wire. One of the two has to own the glide; the one being flown owns it.
     */
    public boolean isDriving(LivingEntity target) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null
                && player.isFallFlying()
                && target != null
                && target == this.target
                && this.aimPoint != null;
    }
}
