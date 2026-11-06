package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;

/**
 * ElytraTarget — port of PounchClient's {@code lightning.product.F_3698_k} together with the
 * pieces of its aura that the module actually drives:
 *
 * <ul>
 *   <li>{@code a_1344_X.n_1700_B(Entity)} — the lead point (look+velocity blend, feet-anchored)</li>
 *   <li>{@code AttackAura.G_564_y(LivingEntity)} / {@code e_2887_G()} — the fly-by hitbox gate
 *       (target's box pushed forward by its own motion, hit only once the target is behind our
 *       velocity vector)</li>
 *   <li>{@code AttackAura.n_1700_B(b_2312_j)} — the firework impulse redirect, applied from
 *       {@link win.furryhook.mixin.player.FireworkRocketEntityMixin}</li>
 * </ul>
 *
 * Setting names, defaults and every numeric constant are kept from the source.
 */
public final class ElytraTargetFeature extends Feature {

    // ---- F_3698_k ----
    /** {@code F_3698_k.v_4262_N} — target must be further than this to count as an overtake target. */
    private static final double MIN_TARGET_DISTANCE = 5.0D;
    /** Min own horizontal velocity squared before the "target is behind us" test means anything. */
    private static final double MIN_OWN_VEL_SQ = 0.0064D;
    /** Half the target's hitbox height — the point the heading test aims at. */
    private static final double TARGET_HEIGHT_FRACTION = 0.5D;
    /** Degenerate-vector guard. */
    private static final double MIN_DIFF_SQ = 1.0E-6D;
    /** dot(our velocity, eye to target) below this = target sits behind our flight path. */
    private static final double BEHIND_DOT = -0.1D;

    // ---- a_1344_X.n_1700_B ----
    /** Target displacement squared per tick below which the predict is skipped (1 block = 20 b/s). */
    private static final double PREDICT_MIN_MOVE_SQ = 1.0D;

    // ---- AttackAura.G_564_y (flybox) ----
    private static final double FLYBOX_MIN_MOTION_SQ = 1.0E-4D;
    private static final double FLYBOX_IDLE_GROW = 0.1D;
    private static final double FLYBOX_GROW_X = 0.2D;
    private static final double FLYBOX_GROW_Y = 0.12D;
    private static final double FLYBOX_GROW_Z = 0.2D;

    // ---- AttackAura.n_1700_B(b_2312_j) (impulse redirect) ----
    /** cos between the previous and the wanted heading below which we snap instead of blending. */
    private static final double REVERSE_COS = -0.5D;
    /** Blend weight between the previous horizontal impulse and the wanted one. */
    private static final double BLEND = 0.5D;
    /** Below this the current motion is unusable and the last position delta is used instead. */
    private static final double MIN_HORIZ_LEN = 0.03D;
    private static final double EPS = 1.0E-4D;

    public static final String PRIORITY_ELYTRAS_FIRST = "Сперва элитры";
    private static final String PRIORITY_NORMAL = "Обычно";

    public final ModeSetting priority = register(new ModeSetting(
            "Приоритет цели", PRIORITY_NORMAL, PRIORITY_NORMAL, PRIORITY_ELYTRAS_FIRST
    ));

    public final BooleanSetting overtake = register(new BooleanSetting(
            "Перегон", true
    ));

    public final BooleanSetting bravoFFA = register(new BooleanSetting(
            "BravoFFA", false
    ).visibleWhen(() -> overtake.getValue()));

    public final NumberSetting predictPower = register(new NumberSetting(
            "Сила предикта", 3.0D, 1.0D, 5.0D, 0.1D, ""
    ));

    /** {@code AttackAura.Q_4569_t}: while gliding the aura keeps targets valid out to this range. */
    public final NumberSetting targetRange = register(new NumberSetting(
            "Дистанция цели", 64.0D, 5.0D, 100.0D, 1.0D, " blocks"
    ));

    /** {@code AttackAura.resolverv2}: gates the flybox hit check below. Off = plain aura reach. */
    public final BooleanSetting hitboxResolver = register(new BooleanSetting(
            "Резолвер хитбокса", true
    ));

    public final BooleanSetting renderPredict = register(new BooleanSetting(
            "Показывать предикт", true
    ));

    /** {@code AttackAura.L_1362_X} — last horizontal impulse written by the redirect. */
    private Vec3 lastImpulse = Vec3.ZERO;

    public ElytraTargetFeature() {
        super("ElytraTarget", "Топовый перегон на элитре", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    public static ElytraTargetFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(ElytraTargetFeature.class);
    }

    @Override
    protected void onDisable() {
        this.lastImpulse = Vec3.ZERO;
    }

    // ------------------------------------------------------------------ F_3698_k surface

    /** {@code F_3698_k.h_1847_R} — module on AND {@code Перегон} on. */
    public boolean isOvertakeActive() {
        return isEnabled() && overtake.getValue();
    }

    /** {@code F_3698_k.Q_4569_t} — module on AND {@code BravoFFA} on AND {@code Перегон} on. */
    public boolean isBravoActive() {
        return isEnabled() && bravoFFA.getValue() && overtake.getValue();
    }

    /**
     * {@code F_3698_k.n_1700_B(Entity)} — overtake is on, the target is gliding and it is further
     * than {@link #MIN_TARGET_DISTANCE} away. This is the per-target "overtake mode" switch the
     * aura reads before it hands the flight over to the predict.
     */
    public boolean isOvertakeTarget(LivingEntity target) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!isOvertakeActive() || player == null || target == null) {
            return false;
        }
        return target.isFallFlying() && player.distanceTo(target) > MIN_TARGET_DISTANCE;
    }

    /**
     * {@code F_3698_k.n_1700_B(LivingEntity)} — the target sits behind our horizontal velocity
     * (more than ~96 degrees off it). This is what turns a fly-by into a hit: on a head-on pass
     * the swing is held until the moment we are actually level with / past the target.
     */
    public boolean isTargetBehind(LivingEntity target) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || target == null) {
            return false;
        }
        Vec3 motion = player.getDeltaMovement();
        Vec3 horizMotion = new Vec3(motion.x, 0.0D, motion.z);
        if (horizMotion.lengthSqr() < MIN_OWN_VEL_SQ) {
            return false;
        }
        horizMotion = horizMotion.normalize();

        Vec3 toTarget = target.position()
                .add(0.0D, target.getBbHeight() * TARGET_HEIGHT_FRACTION, 0.0D)
                .subtract(player.getEyePosition());
        Vec3 horizTo = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (horizTo.lengthSqr() < MIN_DIFF_SQ) {
            return false;
        }
        return horizMotion.dot(horizTo.normalize()) < BEHIND_DOT;
    }

    // ------------------------------------------------------------------ predict

    /**
     * {@code a_1344_X.n_1700_B} gate: overtake on, both sides gliding. Static because the aura
     * calls it before it knows whether the module exists.
     */
    public static boolean isPredicting(LocalPlayer player, LivingEntity target) {
        ElytraTargetFeature feature = getEnabled();
        if (feature == null || player == null || target == null) {
            return false;
        }
        return feature.overtake.getValue() && player.isFallFlying() && target.isFallFlying();
    }

    /**
     * {@code a_1344_X.n_1700_B(Entity)} — the lead point, returned as an absolute position.
     *
     * <p>Below one block of displacement per tick the source drops the predict entirely and aims
     * at the target's feet. Above it, the heading is {@code normalize(look + normalize(velocity))}
     * — the look component pulls the point into a turn the target has started but its velocity has
     * not caught up with yet — pushed out by {@code Сила предикта} blocks from the hitbox centre
     * and then dropped back down to feet level.
     */
    public Vec3 aimPoint(LocalPlayer player, LivingEntity target) {
        if (player == null || target == null) {
            return Vec3.ZERO;
        }
        if (!isPredicting(player, target)) {
            return target.getEyePosition();
        }

        double dx = target.getX() - target.xOld;
        double dy = target.getY() - target.yOld;
        double dz = target.getZ() - target.zOld;
        if (dx * dx + dy * dy + dz * dz < PREDICT_MIN_MOVE_SQ) {
            return target.position();
        }

        double forward = predictPower.getValue();
        double half = target.getBbHeight() * TARGET_HEIGHT_FRACTION;

        Vec3 centre = target.position().add(0.0D, half, 0.0D);
        Vec3 velocity = target.getDeltaMovement();
        Vec3 velDir = velocity.lengthSqr() > FLYBOX_MIN_MOTION_SQ ? velocity.normalize() : Vec3.ZERO;
        Vec3 combined = target.getLookAngle().add(velDir).normalize();

        Vec3 predicted = centre.add(combined.scale(forward));
        return new Vec3(predicted.x, predicted.y - half, predicted.z);
    }

    /** Kept for {@code AuraFeature}'s "Таргет" movement correction, which follows the lead point. */
    public Vec3 predictPoint(LivingEntity target, float ignoredTicks) {
        return aimPoint(Minecraft.getInstance().player, target);
    }

    public float predictTicks() {
        return predictPower.getValue().floatValue();
    }

    // ------------------------------------------------------------------ aura integration

    /** Range fed to {@code AuraFeature#getMaxAimRange()} while we are gliding. */
    public double aimRange() {
        return targetRange.getValue();
    }

    /** {@code AttackAura.U_1241_n} — gliding targets outrank everything else in the sort. */
    public static boolean prefersGlidingTargets(LocalPlayer player) {
        ElytraTargetFeature feature = getEnabled();
        return feature != null
                && player != null
                && player.isFallFlying()
                && feature.priority.is(PRIORITY_ELYTRAS_FIRST);
    }

    /**
     * {@code AttackAura.e_2887_G} entry condition: the flybox gate replaces the reach check only
     * while both sides are gliding and the hitbox resolver is on. {@link ElytraResolverFeature}
     * runs its own approach/retreat distances, so it takes the gate back when it is enabled.
     */
    public static boolean overridesReach(LocalPlayer player, LivingEntity target) {
        ElytraTargetFeature feature = getEnabled();
        if (feature == null || player == null || target == null) {
            return false;
        }
        if (ElytraResolverFeature.getEnabled() != null) {
            return false;
        }
        return feature.overtake.getValue()
                && feature.hitboxResolver.getValue()
                && player.isFallFlying()
                && target.isFallFlying();
    }

    /**
     * {@code AttackAura.e_2887_G} — the target has to be behind us, and its own hitbox pushed
     * {@code max(1, Сила предикта)} blocks along its motion has to touch us.
     */
    public boolean allowsAttack(LocalPlayer player, LivingEntity target) {
        if (player == null || target == null) {
            return false;
        }
        if (!isTargetBehind(target)) {
            return false;
        }
        AABB flyBox = flyBox(target);
        return flyBox.intersects(player.getBoundingBox()) || flyBox.contains(player.getEyePosition());
    }

    /** {@code AttackAura.G_564_y(LivingEntity)}. */
    private AABB flyBox(LivingEntity target) {
        AABB current = target.getBoundingBox();
        Vec3 motion = targetMotion(target);
        if (motion.lengthSqr() < FLYBOX_MIN_MOTION_SQ) {
            return current.inflate(FLYBOX_IDLE_GROW);
        }
        double lead = Math.max(1.0D, predictPower.getValue());
        return current.move(motion.normalize().scale(lead))
                .inflate(FLYBOX_GROW_X, FLYBOX_GROW_Y, FLYBOX_GROW_Z);
    }

    /** {@code AttackAura.P_1922_E(LivingEntity)} — velocity, falling back to the position delta. */
    private static Vec3 targetMotion(LivingEntity target) {
        Vec3 motion = target.getDeltaMovement();
        if (motion.lengthSqr() > FLYBOX_MIN_MOTION_SQ) {
            return motion;
        }
        return target.position().subtract(new Vec3(target.xOld, target.yOld, target.zOld));
    }

    // ------------------------------------------------------------------ firework impulse redirect

    /**
     * {@code AttackAura.n_1700_B(b_2312_j)} — rewrites the motion the firework boost is about to
     * be applied to, so every rocket pushes at the predicted point instead of wherever the head
     * happens to be aimed. Y is never touched.
     *
     * <p>Called from {@link win.furryhook.mixin.player.FireworkRocketEntityMixin} with the player's
     * current velocity; returns the same vector unchanged when the module is not driving.
     */
    public static Vec3 redirectFireworkImpulse(LocalPlayer player, Vec3 impulse) {
        ElytraTargetFeature feature = getEnabled();
        if (feature == null || player == null) {
            return impulse;
        }
        return feature.redirect(player, impulse);
    }

    private Vec3 redirect(LocalPlayer player, Vec3 impulse) {
        AuraFeature aura = AuraFeature.getEnabled();
        LivingEntity target = aura == null ? null : aura.getTarget();
        if (target == null || !player.isFallFlying() || !isOvertakeTarget(target)) {
            this.lastImpulse = impulse;
            return impulse;
        }

        // BravoFFA: keep the impulse on the course we already fly, only rebuilt from the clean
        // horizontal heading — no drift, no aim-driven sideways component.
        if (isBravoActive() && isTargetBehind(target)) {
            Vec3 forward = lockToCourse(player, impulse);
            this.lastImpulse = forward == null ? impulse : forward;
            return this.lastImpulse;
        }

        Vec3 toTarget = aimPoint(player, target).subtract(player.getEyePosition());
        Vec3 horizDir = new Vec3(toTarget.x, 0.0D, toTarget.z);
        if (toTarget.lengthSqr() < EPS || horizDir.lengthSqr() < EPS) {
            this.lastImpulse = impulse;
            return impulse;
        }
        horizDir = horizDir.normalize();

        Vec3 lastHoriz = new Vec3(lastImpulse.x, 0.0D, lastImpulse.z);
        double lastLen = Math.sqrt(lastHoriz.x * lastHoriz.x + lastHoriz.z * lastHoriz.z);
        double currentLen = Math.sqrt(impulse.x * impulse.x + impulse.z * impulse.z);
        if (lastLen < EPS && currentLen < EPS) {
            this.lastImpulse = impulse;
            return impulse;
        }

        if (lastLen > EPS && lastHoriz.normalize().dot(horizDir) < REVERSE_COS) {
            // Hard reversal: snap onto the new heading but never gain speed doing it.
            double limited = Math.min(currentLen, lastLen);
            Vec3 snapped = horizDir.scale(limited);
            this.lastImpulse = new Vec3(snapped.x, impulse.y, snapped.z);
            return this.lastImpulse;
        }

        Vec3 desired = horizDir.scale(currentLen);
        Vec3 blended = lastLen > EPS
                ? new Vec3(Mth.lerp(BLEND, lastHoriz.x, desired.x), 0.0D, Mth.lerp(BLEND, lastHoriz.z, desired.z))
                : desired;
        this.lastImpulse = new Vec3(blended.x, impulse.y, blended.z);
        return this.lastImpulse;
    }

    /** {@code AttackAura.P_1922_E(Vec3)} — impulse magnitude kept, direction forced onto our course. */
    private static Vec3 lockToCourse(LocalPlayer player, Vec3 impulse) {
        Vec3 motion = player.getDeltaMovement();
        Vec3 horiz = new Vec3(motion.x, 0.0D, motion.z);
        double len = horiz.length();
        if (len < MIN_HORIZ_LEN) {
            horiz = new Vec3(player.getX() - player.xOld, 0.0D, player.getZ() - player.zOld);
            len = horiz.length();
        }
        if (len < EPS) {
            return null;
        }
        horiz = horiz.normalize();
        double speed = Math.sqrt(impulse.x * impulse.x + impulse.z * impulse.z);
        Vec3 out = horiz.scale(speed);
        return new Vec3(out.x, impulse.y, out.z);
    }

    // ------------------------------------------------------------------ render

    /** {@code c_776_E} draws a box at the lead point; here it is the projected marker. */
    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!renderPredict.getValue()) {
            return;
        }
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        AuraFeature aura = AuraFeature.getEnabled();
        LivingEntity target = aura == null ? null : aura.getTarget();
        if (target == null || !isPredicting(player, target)) {
            return;
        }
        Render3DUtil.ScreenPoint point = Render3DUtil.projectToScreen(mc, aimPoint(player, target));
        if (point == null) {
            return;
        }
        float size = 8.0F;
        float half = size / 2.0F;
        int accent = 0xFF000000 | Theme.getAccent();
        Render2DUtil.rect(point.x() - half - 1.0F, point.y() - half - 1.0F, size + 2.0F, size + 2.0F)
                .color(0xAA000000)
                .draw();
        Render2DUtil.rect(point.x() - half, point.y() - half, size, size)
                .color(accent)
                .draw();
    }
}
