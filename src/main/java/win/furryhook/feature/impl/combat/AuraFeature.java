package win.furryhook.feature.impl.combat;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import win.furryhook.context.RotationContext;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.utils.inventory.InventoryUtil;
import win.furryhook.utils.render.target.SoulImpulse;
import win.furryhook.feature.impl.movement.AirStuckFeature;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.PlayerInputEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.combat.AuraUtil;
import win.furryhook.utils.combat.PredictUtils;
import win.furryhook.utils.combat.MultipointUtil;
import win.furryhook.utils.combat.neuro.NeuroDataset;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.ScaleUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import win.furryhook.utils.text.ChatUtil;
import win.furryhook.utils.combat.rotations.*;
import win.furryhook.utils.render.world.StormState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rewritten to closely mirror nuclear's {@code AttackAura} module: same settings
 * layout (sort/rotation mode/targets/movement correction/combat settings), same
 * attack gating (cps limit + crit gating), same sprint-reset semantics. Target ESP
 * (marker/ghosts/circle + color) is a furryhook-only addition kept from before, as
 * is {@code clientLook} which two render mixins depend on directly.
 */
public final class AuraFeature extends Feature {
    public static LivingEntity target = null;
    public static double bpsTarget = 0.0D;

    private static final String TARGET_ESP_MARKER = "Marker";
    private static final String TARGET_ESP_GHOSTS = "Ghosts";
    private static final String TARGET_ESP_CIRCLE = "Circle";
    private static final String TARGET_ESP_LIGHTNING = "Молнии";
    private static final String TARGET_ESP_SOULS = "Души";

    public final ModeSetting targetEsp = register(new ModeSetting(
            "Target ESP",
            TARGET_ESP_MARKER,
            TARGET_ESP_MARKER,
            TARGET_ESP_GHOSTS,
            TARGET_ESP_CIRCLE,
            TARGET_ESP_LIGHTNING,
            TARGET_ESP_SOULS
    ));

    public final NumberSetting soulsCount = register(new NumberSetting(
            "Souls Count", 4.0D, 1.0D, 8.0D, 1.0D, ""
    ).visibleWhen(this::usesSoulsTargetEsp));
    public final NumberSetting soulsSize = register(new NumberSetting(
            "Souls Size", 1.0D, 0.4D, 2.5D, 0.1D, "x"
    ).visibleWhen(this::usesSoulsTargetEsp));
    public final NumberSetting soulsSpeed = register(new NumberSetting(
            "Souls Speed", 1.0D, 0.4D, 2.5D, 0.1D, "x"
    ).visibleWhen(this::usesSoulsTargetEsp));
    public final NumberSetting soulsTrail = register(new NumberSetting(
            "Souls Trail", 1.0D, 0.3D, 2.5D, 0.1D, "x"
    ).visibleWhen(this::usesSoulsTargetEsp));
    public final BooleanSetting soulsThroughWalls = register(new BooleanSetting(
            "Souls Through Walls", true
    ).visibleWhen(this::usesSoulsTargetEsp));

    public final ModeSetting targetEspColorMode = register(ColorMode.setting());
    public final ColorSetting targetEspColor = register(new ColorSetting(
            "Target ESP Color",
            0xFF00FF88
    ).visibleWhen(() -> ColorMode.isCustom(this.targetEspColorMode)));

    public final ModeSetting sortMode = register(new ModeSetting(
            "Sort Mode",
            "По здоровью",
            "По здоровью", "По дистанции", "По броне", "По FOV"
    ));

    // Furry is a test rotation: it stays out of the dropdown and is only reachable by
    // setting "Rotation Mode": "Furry" in the config.
    public final ModeSetting componentMode = register(new ModeSetting(
            "Rotation Mode",
            "ReallyWorld",
            "ReallyWorld", "Grim", "Snap", "Funtime", "FuntimeDick", "Funfick", "Polar", "Spooky", "HolyWorld", "BMW", "Legit Snap", "Sloth", "Furry", "Neuro", "Test", "SpaceTime", "FT-New", "FunTest", "Custom"
    ).hideModes("Furry"));

    public final NumberSetting legitSnapFov = register(new NumberSetting(
            "FOV", 25.0D, 5.0D, 90.0D, 1.0D, ""
    ).visibleWhen(() -> componentMode.is("Legit Snap")));

    /**
     * Expensive's own "Rotation Mode" dropdown, carried over with the port: it picks which of its
     * three processors Legit Snap runs, and each one brings its own expiry and prediction window.
     */
    public final ModeSetting legitSnapProcessor = register(new ModeSetting(
            "Snap Processor",
            "Funtime",
            "Funtime", "Smooth", "Grim"
    ).visibleWhen(() -> componentMode.is("Legit Snap")));

    /** {@code attackauraCritsWithSpace}, visible on the same terms it is there: only with crits on. */
    public final BooleanSetting legitSnapCritsWithSpace = register(new BooleanSetting(
            "Snap Crits With Space", false
    ).visibleWhen(() -> (componentMode.is("Legit Snap") || componentMode.is("Funfick"))
            && this.combatSettings.isSelected("Only Criticals")));

    public final NumberSetting funtimeSnapTicks = register(new NumberSetting(
            "Snap Ticks", 3.0D, 2.0D, 8.0D, 1.0D, " ticks"
    ).visibleWhen(() -> componentMode.is("Funtime")));

    public final NumberSetting funtimeSmoothness = register(new NumberSetting(
            "Smoothness", 55.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("Funtime")));

    public final NumberSetting funtimeOffset = register(new NumberSetting(
            "Aim Offset", 1.2D, 0.0D, 5.0D, 0.1D, "°"
    ).visibleWhen(() -> componentMode.is("Funtime")));

    public final NumberSetting funtimeMultipoint = register(new NumberSetting(
            "Multipoint", 3.0D, 1.0D, 5.0D, 1.0D, " points"
    ).visibleWhen(() -> componentMode.is("Funtime")));

    public final NumberSetting dickMinClosure = register(new NumberSetting(
            "Min Closure", 30.0D, 5.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("FuntimeDick")));

    public final NumberSetting dickMaxClosure = register(new NumberSetting(
            "Max Closure", 86.0D, 5.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("FuntimeDick")));

    public final NumberSetting dickSpread = register(new NumberSetting(
            "Aim Spread", 55.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("FuntimeDick")));

    public final NumberSetting dickHoldTicks = register(new NumberSetting(
            "Hold Ticks", 20.0D, 0.0D, 40.0D, 1.0D, " ticks"
    ).visibleWhen(() -> componentMode.is("FuntimeDick")));

    public final NumberSetting polarTolerance = register(new NumberSetting(
            "On-Target Tolerance", 4.0D, 1.0D, 15.0D, 0.5D, "°"
    ).visibleWhen(() -> componentMode.is("Polar")));

    public final NumberSetting polarYawStep = register(new NumberSetting(
            "Max Yaw Step", 35.0D, 5.0D, 90.0D, 1.0D, "°"
    ).visibleWhen(() -> componentMode.is("Polar")));

    public final NumberSetting polarPitchStep = register(new NumberSetting(
            "Max Pitch Step", 22.0D, 5.0D, 90.0D, 1.0D, "°"
    ).visibleWhen(() -> componentMode.is("Polar")));

    public final NumberSetting polarAimHeight = register(new NumberSetting(
            "Aim Height", 72.0D, 30.0D, 95.0D, 1.0D, "%"
    ).visibleWhen(() -> componentMode.is("Polar")));

    public final ModeSetting furryMode = register(new ModeSetting(
            "Furry Mode",
            "Spin + Jitter",
            "Spin + Jitter", "Spin 360", "Jitter"
    ).visibleWhen(() -> componentMode.is("Furry")));

    public final NumberSetting furrySpinTicks = register(new NumberSetting(
            "Spin Ticks", 6.0D, 3.0D, 12.0D, 1.0D, " ticks"
    ).visibleWhen(() -> componentMode.is("Furry") && !furryMode.is("Jitter")));

    public final NumberSetting furryJitter = register(new NumberSetting(
            "Jitter", 100.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("Furry") && !furryMode.is("Spin 360")));

    public final BooleanSetting displayFov = register(new BooleanSetting(
            "Display FOV", true
    ).visibleWhen(() -> componentMode.is("Legit Snap")));

    public final BooleanSetting neuroLearning = register(new BooleanSetting(
            "Learning Mode", false
    ).visibleWhen(() -> componentMode.is("Neuro")));

    public final ModeSetting neuroPlayback = register(new ModeSetting(
            "Neuro Playback",
            "Blend",
            "Blend", "Replay"
    ).visibleWhen(() -> componentMode.is("Neuro")));

    public final NumberSetting neuroNeighbours = register(new NumberSetting(
            "Blended Samples", 6.0D, 1.0D, 16.0D, 1.0D, ""
    ).visibleWhen(() -> componentMode.is("Neuro") && neuroPlayback.is("Blend")));

    public final NumberSetting neuroVariation = register(new NumberSetting(
            "Variation", 35.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("Neuro") && neuroPlayback.is("Blend")));

    public final BooleanSetting neuroAssist = register(new BooleanSetting(
            "Hit Assist", true
    ).visibleWhen(() -> componentMode.is("Neuro")));

    public final NumberSetting neuroAssistLimit = register(new NumberSetting(
            "Assist Limit", 100.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("Neuro") && neuroAssist.getValue()));

    /** Писать в датасет только размены один на один — самая чистая выборка, какая бывает. */
    public final BooleanSetting neuroDuelOnly = register(new BooleanSetting(
            "Learn 1v1 Only", false
    ).visibleWhen(() -> componentMode.is("Neuro") && neuroLearning.getValue()));

    /**
     * Насколько подбор держится за кадры из такого же боя, каким идёт сейчас. В свалке
     * прицел перекидывают, в дуэли ведут — смешивать их в одно среднее незачем.
     */
    public final NumberSetting neuroDuelBias = register(new NumberSetting(
            "1v1 Priority", 50.0D, 0.0D, 100.0D, 5.0D, "%"
    ).visibleWhen(() -> componentMode.is("Neuro")));

    /** Реплей берёт только те трассы, что кончились ударом. */
    public final BooleanSetting neuroReplayHits = register(new BooleanSetting(
            "Replay Landed Only", true
    ).visibleWhen(() -> componentMode.is("Neuro") && neuroPlayback.is("Replay")));

    public final ModeSetting neuroDataset = register(new ModeSetting(
            "Neuro Dataset",
            NeuroDataset.DEFAULT_NAME,
            NeuroDataset.DEFAULT_NAME
    ).visibleWhen(() -> componentMode.is("Neuro")));

    public final BooleanSetting neuroOverlay = register(new BooleanSetting(
            "Show Samples", true
    ).visibleWhen(() -> componentMode.is("Neuro")));

    public final MultiSelectSetting targets = register(new MultiSelectSetting(
            "Targets",
            List.of("Игроки", "Голые", "Невидимки", "Голые невидимки"),
            "Игроки", "Голые", "Мобы", "Друзья", "Невидимки", "Голые невидимки"
    ));

    public final ModeSetting correctionType = register(new ModeSetting(
            "Movement Correction",
            "Сфокусированная",
            "Свободная", "Преследование", "Сфокусированная", "Таргет"
    ));

    public final NumberSetting attackRange = register(new NumberSetting(
            "Attack Range", 3.0D, 2.5D, 6.0D, 0.1D, " blocks"
    ));

    public final NumberSetting rotateDistance = register(new NumberSetting(
            "Rotate Distance", 0.0D, 0.0D, 3.0D, 0.1D, " blocks"
    ));

    public final NumberSetting attackDelay = register(new NumberSetting(
            "Attack Delay", 380.0D, 250.0D, 600.0D, 10.0D, "ms"
    ));

    public final NumberSetting noPetRange = register(new NumberSetting(
            "No-Pet Range", 5.0D, 3.0D, 6.0D, 0.1D, " blocks"
    ));

    /**
     * Two position packets around a swing hand the server a fall it never saw, which forces a crit
     * in the air. Every anticheat past vanilla reads that pair straight off the movement stream, so
     * it is off by default — with it off the aura instead waits out a real descent frame.
     */
    public final BooleanSetting packetCrits = register(new BooleanSetting(
            "Пакетные криты", false
    ));

    public final ModeSetting sprintMode = register(new ModeSetting(
            "Sprint Mode",
            "Легитный",
            "Обычный", "Легитный"
    ));

    public final MultiSelectSetting combatSettings = register(new MultiSelectSetting(
            "Combat Settings",
            // Selected by default. "Through Walls" is deliberately not among them: with it on,
            // Bypass Walls RW sends START/STOP_DESTROY_BLOCK at every solid block between us and
            // the target, and Grim reads block breaks with no line of sight straight off the
            // packet stream. It stays in the option list below, just not on by accident.
            List.of("Only Criticals", "Turbo Criticals", "Unblock Shield", "Break Shield", "Smart Criticals", "Don't Hit While Eating"),
            // Available options.
            "Only Criticals", "Turbo Criticals", "Unblock Shield", "Break Shield", "Smart Criticals", "Through Walls", "Don't Hit While Eating"
    ));

    public final BooleanSetting bypassWalls = register(new BooleanSetting(
            "Bypass Walls RW", false
    ).visibleWhen(() -> combatSettings.isSelected("Through Walls")));

    public final BooleanSetting maceSwap = register(new BooleanSetting(
            "Mace Swap", false
    ));

    public final NumberSetting maceHeight = register(new NumberSetting(
            "Mace Height", 3.0D, 1.0D, 10.0D, 0.5D, " blocks"
    ).visibleWhen(this.maceSwap::getValue));

    public final BooleanSetting clientLook = register(new BooleanSetting(
            "Client Look", false
    ));

    // ============ Elytra Target Settings (Javelin-style integrated into Aura) ============
    public final BooleanSetting predictElytra = register(new BooleanSetting(
            "Предикт на элитрах", true
    ));

    public final ModeSetting predictMode = register(new ModeSetting(
            "Предикт", "Обычный", "Обычный", "Легитный"
    ).visibleWhen(predictElytra::getValue));

    /** Height of the packet-only hop that books fall distance for an in-air crit. */
    private static final double CRIT_HOP = 0.0625D;

    /** AlphaDLC {@code STICKY_TARGET_RANGE_BONUS} — grace range for the target already engaged. */
    private static final double STICKY_RANGE_BONUS = 0.75D;

    /** AlphaDLC {@code RANGE_MARGIN} — hitbox growth applied before the attack ray is tested. */
    private static final double RANGE_MARGIN = 0.153D;

    // ============ Elytra Target State (Javelin-style) ============
    /** Predicted elytra position for rendering cyan box */
    private Vec3 predictedElytraPos;
    /** Target being predicted for elytra combat */
    private LivingEntity elytraTarget;


    private long cpsLimit = 0L;

    private final ReallyworldRotation reallyworldRotation = new ReallyworldRotation();
    private final GrimRotation grimRotation = new GrimRotation();
    private final SnapRotation snapRotation = new SnapRotation();
    private final FuntimeRotation funtimeRotation = new FuntimeRotation();
    private final FuntimeDick funtimeDick = new FuntimeDick();
    private final PolarRotation polarRotation = new PolarRotation();
    private final SpookyRotation spookyRotation = new SpookyRotation();
    private final HolyWorldRotation holyWorldRotation = new HolyWorldRotation();
    private final BMWRotation bmwRotation = new BMWRotation();
    private final LegitSnapRotation legitSnapRotation = new LegitSnapRotation();
    private final FunfickRotation funfickRotation = new FunfickRotation();
    private final SpaceTimeRotation spaceTimeRotation = new SpaceTimeRotation();
    private final SlothRotation slothRotation = new SlothRotation();
    private final FurryRotation furryRotation = new FurryRotation();
    private final NeuroRotation neuroRotation = new NeuroRotation();
    private final TestRotation testRotation = new TestRotation();
    
    // Wild client rotations - ported 1:1
    private final FTNewRotation ftNewRotation = new FTNewRotation();
    private final FunTestRotation funTestRotation = new FunTestRotation();
    private final CustomRotation customRotation = new CustomRotation();

    /** Last dataset name the dropdown asked for, so a failed load is not retried every tick. */
    private String requestedNeuroDataset;

    public AuraFeature() {
        super("Aura", "Automatically attacks selected entities", FeatureCategory.COMBAT, BindSetting.UNBOUND);
        // Регистрация 3D рендера закомментирована - нужно добавить через вашу систему рендеров
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(this::onRender3D);
    }

    public static AuraFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(AuraFeature.class);
    }

    public static AuraFeature getMarkerFeature() {
        return FeatureManager.INSTANCE.getFeature(AuraFeature.class);
    }

    public LivingEntity getTarget() {
        return isEnabled() ? target : null;
    }

    public LivingEntity getCurrentTarget() {
        return getTarget();
    }

    public boolean usesGhostTargetEsp() {
        return TARGET_ESP_GHOSTS.equals(this.targetEsp.getValue());
    }

    public boolean usesCircleTargetEsp() {
        return TARGET_ESP_CIRCLE.equals(this.targetEsp.getValue());
    }

    public boolean usesRhombusTargetEsp() {
        return false;
    }

    public boolean usesLightningTargetEsp() {
        return TARGET_ESP_LIGHTNING.equals(this.targetEsp.getValue());
    }

    public boolean usesSoulsTargetEsp() {
        return TARGET_ESP_SOULS.equals(this.targetEsp.getValue());
    }

    public int soulCount() {
        return (int) Math.round(this.soulsCount.getValue());
    }

    public float soulSize() {
        return this.soulsSize.getValue().floatValue();
    }

    public float soulSpeed() {
        return this.soulsSpeed.getValue().floatValue();
    }

    public float soulTrail() {
        return this.soulsTrail.getValue().floatValue();
    }

    public boolean soulsThroughWalls() {
        return this.soulsThroughWalls.getValue();
    }

    public int getMarkerColor() {
        return ColorMode.resolve(this.targetEspColorMode, this.targetEspColor);
    }

    /**
     * Auto-jump gate for {@link win.furryhook.feature.impl.movement.AutoJumpFeature}. Now
     * fires only when a hit is about to land — swing charged, target inside reach, on ground,
     * crit conditions otherwise satisfied. Stops the classic "bunny hop for no reason" waste
     * that used to pump fall damage without producing crits.
     */
    public boolean shouldAutoJump(LocalPlayer player) {
        if (!isEnabled() || target == null) return false;
        if (!combatSettings.isSelected("Only Criticals") && !combatSettings.isSelected("Turbo Criticals")) return false;
        if (checkReturn(player)) return false;
        if (player.isFallFlying() || player.getAbilities().flying) return false;
        if (getStrictDistance(target) >= attackDistance()) return false;

        // Swing must be almost full so the leap lands on a ready hit — no wasted airtime.
        float swing = player.getAttackStrengthScale(0.0F);
        float minSwing = combatSettings.isSelected("Turbo Criticals") ? 0.82F : 0.9F;
        if (swing < minSwing) return false;

        // Same guards as vanilla crit eligibility, minus the fallDistance check (we are still on the ground).
        if (player.hasEffect(MobEffects.LEVITATION) || player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || player.isEyeInFluid(net.minecraft.tags.FluidTags.LAVA)) return false;
        if (player.onClimbable() || player.isPassenger()) return false;
        return true;
    }

    @Override
    protected void onEnable() {
        cpsLimit = System.currentTimeMillis();
        resetRotationState();
    }

    @Override
    protected void onDisable() {
        target = null;
        // Closes the swing in progress and writes the dataset out before the state goes.
        neuroRotation.setLearning(false);
        resetRotationState();
        RotationContext.clear(); // Clear rotation context to restore player control
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        target = null;
        NeuroDataset.INSTANCE.saveIfDirty();
        RotationContext.clear();
    }

    @EventTarget
    public void onInput(PlayerInputEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (target == null || !isValidTarget(target)) {
            target = findTarget();
        }
        if (target == null) return;

        boolean rotateActive = RotationContext.isActive();
        boolean controlling = rotateActive || target != null;

        if (correctionType.is("Свободная")) {
            // Free means the keys keep meaning what the camera shows. While the aura spoofs the
            // yaw the player's real view lives in the free-look angle, so that is the intent the
            // keys are resolved from — handing the spoofed yaw in here (what focused does) makes
            // every key resolve against the target instead, and the player walks into it.
            if (controlling && !this.clientLook.getValue()) {
                fixMovement(event, rotateActive ? RotationContext.getFreeYaw() : player.getYRot());
            }
        } else if (correctionType.is("Сфокусированная")) {
            // Focused deliberately keeps movement tied to the spoofed rotation, so strafing
            // circles the target instead of the camera.
            if (controlling && !this.clientLook.getValue()) {
                fixMovement(event, rotateActive ? RotationContext.getServerYaw() : player.getYRot());
            }
        } else if (correctionType.is("Преследование")) {
            if (!this.clientLook.getValue()) {
                double dx = target.getX() - player.getX();
                double dz = target.getZ() - player.getZ();
                float chaseYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                fixMovement(event, chaseYaw);
            }
        } else if (correctionType.is("Таргет")) {
            if (!rotateActive) {
                updateRotation();
            }
            // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: движение к позиции предикта
            Vec3 followPos = (player.isFallFlying() && target.isFallFlying() && predictElytra.getValue())
                    ? realPredict(target)
                    : target.position();
            moveToPosition(event, followPos, RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot());
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying()) {
            reset();
            return;
        }

        // Kept in step even with no target in sight: switching learning off has to close the
        // swing and write the dataset out there and then, not whenever a fight next starts.
        if (componentMode.is("Neuro")) {
            configureNeuroRotation();
        }

        if (target != null) {
            Vec3 delta = target.position().subtract(new Vec3(target.xo, target.yo, target.zo));
            bpsTarget = delta.length() * 20.0D;
            
            // ============ Elytra Predict Logic (Javelin-style) ============
            // Логика из Javelin AttackAura: если оба планируют, вычисляем предикт
            Player fakePlayer = null; // TODO: FreeCamera support if needed
            if ((fakePlayer != null ? fakePlayer : player).isFallFlying() && target.isFallFlying()) {
                Vec3 point = realPredict(target);
                predictedElytraPos = point;
                elytraTarget = target;
            } else {
                predictedElytraPos = null;
                elytraTarget = null;
            }
        }

        if (target == null || !isValidTarget(target)) {
            target = findTarget();
        }

        if (target == null) {
            // FuntimeDick keeps the head where it was for its hold window instead of letting the
            // aura snap it back the same tick the target drops. Only that mode holds; every other
            // one releases exactly as before.
            if (componentMode.is("FuntimeDick") && funtimeDick.isHolding()) {
                funtimeDick.tick(player, null, player.getEyePosition(), false);
                return;
            }
            // Legit Snap holds for the same reason, but the hold is not decoration there: Expensive's
            // tracker outlives the target by design — getCurrentStrategy() falls back to the sticky
            // rotationTarget, so the return curve keeps running after the module stops scheduling and
            // stops only at its own release. Dropping it here and calling disengage() instead would
            // replace the ported curve with this base's generic one on every target loss.
            if (componentMode.is("Legit Snap") && legitSnapRotation.isReturning()) {
                legitSnapRotation.tick(player, null, player.getEyePosition(), false);
                return;
            }
            if (componentMode.is("Funfick") && funfickRotation.isReturning()) {
                funfickRotation.tick(player, null, player.getEyePosition(), false);
                return;
            }
            if (componentMode.is("SpaceTime") && spaceTimeRotation.isReturning()) {
                spaceTimeRotation.tick(player, null, player.getEyePosition(), false);
                return;
            }
            reset();
            return;
        }

        // Minced AttackAura.method899 rotates first and only then runs the attack, because the
        // swing is validated against the rotation the server already holds. On the ground the
        // ordering barely matters; on a fly-by the target moves a whole hitbox between ticks, so
        // attacking on last tick's aim means every ray misses and nothing ever registers.
        // Legit Snap joins the rotate-first branch because that is the order Expensive's own module
        // runs in: it schedules the rotation on the PRE stage and only attacks on POST, so the swing
        // is always tested against the angle this tick produced. Attacking on last tick's aim is what
        // made it behave like a triggerbot — the turn was already under way and every ray was still
        // being cast from where the crosshair had been before it started.
        boolean rotateFirst = player.isFallFlying() || componentMode.is("Legit Snap") || componentMode.is("Funfick") || componentMode.is("SpaceTime") || componentMode.is("Spooky");
        if (rotateFirst) {
            updateRotation();
        }

        if (canAttack() && legitSnapAllowsHit(player)) {
            updateAttack();
        }

        if (!rotateFirst) {
            updateRotation();
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        drawLegitSnapFov(event);
        drawNeuroCounter(event);
    }

    /**
     * Sample counter over the crosshair. While learning it reads as a recorder — blinking
     * dot, banked samples, frames in the swing being taken — because that is exactly what
     * the player is watching for. In playback it just states how much the rotation has to
     * draw on.
     */
    private void drawNeuroCounter(Render2DEvent event) {
        if (!neuroOverlay.getValue() || !componentMode.is("Neuro")) return;

        Minecraft mc = event.getClient();
        if (mc.player == null
                || mc.options.getCameraType() != CameraType.FIRST_PERSON
                || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        boolean learning = neuroRotation.isLearning();
        if (!learning && target == null) return;

        // Счётчик теперь говорит не только «сколько», но и «чего»: сколько замахов дошли до
        // удара и сколько сняты один на один — по ним и видно, чем набит датасет.
        String label = learning
                ? "REC  " + NeuroDataset.INSTANCE.sampleCount()
                        + "  •  " + neuroRotation.swingFrames()
                        + "  •  1v1 " + NeuroDataset.INSTANCE.duelCount()
                : "NEURO  " + NeuroDataset.INSTANCE.sampleCount()
                        + "  •  hit " + NeuroDataset.INSTANCE.hitCount();

        float centerX = event.getGuiGraphicsExtractor().guiWidth() / 2.0F;
        float centerY = event.getGuiGraphicsExtractor().guiHeight() / 2.0F;
        float unit = ScaleUtil.toGuiPixels(1.0F, mc.getWindow().getGuiScale());

        MsdfFont font = UiFonts.sfProDisplay();
        float textSize = 8.0F * unit;
        float letterSpacing = textSize * UiFontStyle.MEDIUM.letterSpacingEm();
        float textWidth = font.measureWidth(label, textSize, letterSpacing);

        float dotSize = learning ? 4.0F * unit : 0.0F;
        float dotGap = learning ? 4.0F * unit : 0.0F;
        float paddingX = 5.0F * unit;
        float paddingY = 3.0F * unit;
        float width = textWidth + dotSize + dotGap + paddingX * 2.0F;
        float height = textSize + paddingY * 2.0F;
        float left = centerX - width * 0.5F;
        float top = centerY - 26.0F * unit - height;

        Render2DUtil.rect(left, top, width, height)
                .color(ColorUtil.rgba(0, 0, 0, 140))
                .radius(height * 0.35F)
                .draw();

        float contentLeft = left + paddingX;
        if (learning) {
            float pulse = 0.45F + 0.55F * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0D));
            Render2DUtil.rect(contentLeft, top + (height - dotSize) * 0.5F, dotSize, dotSize)
                    .color(ColorUtil.multiplyAlpha(0xFFFF4040, pulse))
                    .radius(dotSize * 0.5F)
                    .draw();
            contentLeft += dotSize + dotGap;
        }

        Render2DUtil.text(contentLeft, font.centeredTextY(top + height * 0.5F, textSize), textSize, label)
                .style(UiFontStyle.MEDIUM)
                .color(learning ? 0xFFFFFFFF : 0xFF000000 | Theme.getAccent())
                .draw();
    }

    /**
     * The cone the snap actually engages on, drawn around the crosshair. It is deliberately not
     * gated on having a target: the ring is the setting made visible, and it is only useful while
     * lining a fight up — that is, before there is anything to lock onto. It stays on screen for as
     * long as the mode is selected, and only changes colour to report state.
     */
    private void drawLegitSnapFov(Render2DEvent event) {
        if (!displayFov.getValue() || !componentMode.is("Legit Snap")) return;

        Minecraft mc = event.getClient();
        if (mc.player == null || mc.level == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (mc.getDebugOverlay().showDebugScreen()) return;

        float centerX = event.getGuiGraphicsExtractor().guiWidth() / 2.0F;
        float centerY = event.getGuiGraphicsExtractor().guiHeight() / 2.0F;
        float halfHeight = event.getGuiGraphicsExtractor().guiHeight() / 2.0F;

        // The cone is projected through a tangent ratio, and tan(90°) is not a number this can carry:
        // at the setting's own maximum it comes out as 1.6e16, the radius overflows the float, and
        // the segment count below it — ceil(radius * 2) cast to int — saturates at Integer.MAX_VALUE.
        // That is a two-billion-iteration draw loop on the render thread, every frame, which is a
        // hang, not a slowdown. It only became reachable when this stopped requiring a target.
        double coneDegrees = Math.min(legitSnapFov.getValue(), 89.0D);
        double halfFovDegrees = Math.max(1.0D, mc.options.fov().get() * 0.5D);
        float radius = (float) (halfHeight
                * (Math.tan(Math.toRadians(coneDegrees)) / Math.tan(Math.toRadians(halfFovDegrees))));

        // Past the screen diagonal the ring is entirely off-screen; pin it to the edge rather than
        // drawing a circle nobody can see. At that width the cone really does cover the whole view.
        float maxRadius = (float) Math.hypot(
                event.getGuiGraphicsExtractor().guiWidth(),
                event.getGuiGraphicsExtractor().guiHeight()
        ) * 0.5F;
        if (!Float.isFinite(radius) || radius < 1.0F) return;
        radius = Math.min(radius, maxRadius);

        // Idle the ring is a faint outline; it lights up the instant a hitbox crosses into the cone
        // and goes solid accent while the snap owns the rotation, so the FOV number can be tuned by
        // watching the ring instead of by guessing.
        int color;
        float thickness;
        if (legitSnapRotation.isEngaged()) {
            color = 0xFF000000 | Theme.getAccent();
            thickness = 1.0F;
        } else if (target != null && legitSnapRotation.isTargetInFov()) {
            color = ColorUtil.rgba(255, 255, 255, 220);
            thickness = 0.8F;
        } else {
            color = ColorUtil.rgba(255, 255, 255, 90);
            thickness = 0.6F;
        }

        drawFovRing(event, centerX, centerY, radius, thickness, color);
    }

    private void drawFovRing(Render2DEvent event, float centerX, float centerY, float radius, float thickness, int color) {
        // Hard ceiling. The old count scaled with the radius and nothing bounded it, so the cost of
        // one ring was whatever the projection happened to produce — fine at 25°, a frozen client at
        // 90°. 192 segments is smooth past any radius that fits on a screen.
        int segments = net.minecraft.util.Mth.clamp((int) Math.ceil(radius * 0.6F), 48, 192);
        for (int index = 0; index < segments; index++) {
            double firstAngle = Math.PI * 2.0D * index / segments;
            double secondAngle = Math.PI * 2.0D * (index + 1) / segments;
            float x1 = centerX + (float) Math.cos(firstAngle) * radius;
            float y1 = centerY + (float) Math.sin(firstAngle) * radius;
            float x2 = centerX + (float) Math.cos(secondAngle) * radius;
            float y2 = centerY + (float) Math.sin(secondAngle) * radius;

            float dx = x2 - x1;
            float dy = y2 - y1;
            float segmentLength = (float) Math.sqrt(dx * dx + dy * dy);
            var pose = event.getGuiGraphicsExtractor().pose();
            pose.pushMatrix();
            pose.translate(x1, y1);
            pose.rotate((float) Math.atan2(dy, dx));
            Render2DUtil.rect(0.0F, -thickness * 0.5F, segmentLength, thickness)
                    .color(color)
                    .draw();
            pose.popMatrix();
        }
    }

    /**
     * 3D рендер предикта элитры - cyan box (Javelin-style)
     * TODO: Интегрировать в вашу систему 3D рендеринга
     * Закомментировано из-за отсутствия Fabric API WorldRenderContext
     */
    /*
    private void onRender3D() {
        if (predictedElytraPos == null || elytraTarget == null) return;
        // Render cyan box at predictedElytraPos with pulse animation
        // Use your rendering system here
    }
    */

    private void resetRotationState() {
        reallyworldRotation.reset();
        grimRotation.reset();
        snapRotation.reset();
        funtimeRotation.reset();
        funtimeDick.reset();
        polarRotation.reset();
        spookyRotation.reset();
        holyWorldRotation.reset();
        bmwRotation.reset();
        legitSnapRotation.reset();
        funfickRotation.reset();
        spaceTimeRotation.reset();
        furryRotation.reset();
        neuroRotation.reset();
        testRotation.reset();
        ftNewRotation.reset();
        funTestRotation.reset();
        customRotation.reset();
    }

    private AuraRotation currentRotation() {
        return switch (componentMode.getValue()) {
            case "Grim" -> grimRotation;
            case "Snap" -> snapRotation;
            case "Funtime" -> funtimeRotation;
            case "FuntimeDick" -> funtimeDick;
            case "Polar" -> polarRotation;
            case "Spooky" -> spookyRotation;
            case "HolyWorld" -> holyWorldRotation;
            case "BMW" -> bmwRotation;
            case "Legit Snap" -> legitSnapRotation;
            case "Funfick" -> funfickRotation;
            case "SpaceTime" -> spaceTimeRotation;
            case "Sloth" -> slothRotation;
            case "Furry" -> furryRotation;
            case "Neuro" -> neuroRotation;
            case "Test" -> testRotation;
            case "FT-New" -> ftNewRotation;
            case "FunTest" -> funTestRotation;
            case "Custom" -> customRotation;
            default -> reallyworldRotation;
        };
    }

    private void updateRotation() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || target == null) return;

        boolean attackLikely = canAttack();
        Vec3 targetEyePos = getEffectiveTargetEyePos(player, target, attackLikely);

        AuraRotation rotation = currentRotation();
        if (rotation == legitSnapRotation) {
            configureLegitSnapRotation();
        } else if (rotation == funfickRotation) {
            configureFunfickRotation();
        } else if (rotation == spaceTimeRotation) {
            configureSpaceTimeRotation();
        } else if (rotation == funtimeRotation) {
            configureFuntimeRotation();
        } else if (rotation == funtimeDick) {
            configureFuntimeDick();
        } else if (rotation == polarRotation) {
            configurePolarRotation();
        } else if (rotation == slothRotation) {
            configureSlothRotation();
        } else if (rotation == furryRotation) {
            configureFurryRotation();
        } else if (rotation == neuroRotation) {
            configureNeuroRotation();
        } else if (rotation == ftNewRotation) {
            configureFTNewRotation();
        } else if (rotation == funTestRotation) {
            configureFunTestRotation();
        } else if (rotation == customRotation) {
            configureCustomRotation();
        } else if (rotation == spookyRotation) {
            configureSpookyRotation();
        }

        rotation.tick(player, target, targetEyePos, attackLikely);
    }

    private void configureLegitSnapRotation() {
        legitSnapRotation.setFovAngle(legitSnapFov.getValue().floatValue());
        legitSnapRotation.setMode(legitSnapProcessor.getValue());
        legitSnapRotation.setReach(attackDistance());
        legitSnapRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
        legitSnapRotation.setOnlyCriticalHit(combatSettings.isSelected("Only Criticals"));
        legitSnapRotation.setCritsWithSpace(legitSnapCritsWithSpace.getValue());
    }

    private void configureFunfickRotation() {
        funfickRotation.setReach(attackDistance());
        funfickRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
        funfickRotation.setOnlyCriticalHit(combatSettings.isSelected("Only Criticals"));
        funfickRotation.setCritsWithSpace(legitSnapCritsWithSpace.getValue());
    }

    private void configureSpaceTimeRotation() {
        spaceTimeRotation.setReach(attackDistance());
        spaceTimeRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureFuntimeRotation() {
        funtimeRotation.setSnapTicks(funtimeSnapTicks.getValue().intValue());
        funtimeRotation.setSmoothness((float) (funtimeSmoothness.getValue() / 100.0D));
        funtimeRotation.setOffsetDegrees(funtimeOffset.getValue().floatValue());
        funtimeRotation.setMultipoint(funtimeMultipoint.getValue().intValue());
        funtimeRotation.setReach(attackDistance());
        funtimeRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureFuntimeDick() {
        double min = dickMinClosure.getValue();
        double max = Math.max(min, dickMaxClosure.getValue());
        funtimeDick.setMinClosure((float) (min / 100.0D));
        funtimeDick.setMaxClosure((float) (max / 100.0D));
        funtimeDick.setSpread((float) (dickSpread.getValue() / 100.0D));
        funtimeDick.setHoldTicks(dickHoldTicks.getValue().intValue());
        funtimeDick.setReach(attackDistance());
        funtimeDick.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configurePolarRotation() {
        polarRotation.setTolerance(polarTolerance.getValue().floatValue());
        polarRotation.setMaxYawStep(polarYawStep.getValue().floatValue());
        polarRotation.setMaxPitchStep(polarPitchStep.getValue().floatValue());
        polarRotation.setAimHeight((float) (polarAimHeight.getValue() / 100.0D));
    }

    private void configureFurryRotation() {
        furryRotation.setSpin(!furryMode.is("Jitter"));
        furryRotation.setJitter(!furryMode.is("Spin 360"));
        furryRotation.setSpinTicks(furrySpinTicks.getValue().intValue());
        furryRotation.setJitterStrength((float) (furryJitter.getValue() / 100.0D));
        furryRotation.setReach(attackDistance());
        furryRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureSpookyRotation() {
        spookyRotation.setReach(attackDistance());
        spookyRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureNeuroRotation() {
        neuroDataset.updateModes(NeuroDataset.INSTANCE.names());
        applyNeuroDataset();

        neuroRotation.setLearning(neuroLearning.getValue());
        neuroRotation.setReplay(neuroPlayback.is("Replay"));
        neuroRotation.setNeighbours(neuroNeighbours.getValue().intValue());
        neuroRotation.setVariation((float) (neuroVariation.getValue() / 100.0D));
        neuroRotation.setAssist(neuroAssist.getValue());
        neuroRotation.setAssistLimit((float) (neuroAssistLimit.getValue() / 100.0D));
        neuroRotation.setReach(attackDistance());
        neuroRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
        neuroRotation.setDuelOnly(neuroDuelOnly.getValue());
        neuroRotation.setDuelBias((float) (neuroDuelBias.getValue() / 100.0D));
        neuroRotation.setReplayHitsOnly(neuroReplayHits.getValue());
    }

    private void configureFTNewRotation() {
        ftNewRotation.setReach(attackDistance());
        ftNewRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureFunTestRotation() {
        funTestRotation.setReach(attackDistance());
        funTestRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
    }

    private void configureCustomRotation() {
        customRotation.setReach(attackDistance());
        customRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
        // Default to Balanced preset
        customRotation.loadPreset("Balanced");
    }

    private void configureSlothRotation() {
        slothRotation.setReach(attackDistance());
        slothRotation.setThroughWalls(combatSettings.isSelected("Through Walls"));
        
        // Определяем состояние водного спринта
        Minecraft mc = Minecraft.getInstance();
        boolean waterSprint = mc.player != null 
            && mc.player.isSprinting() 
            && mc.player.isInWater();
        slothRotation.setWaterSprintActive(waterSprint);
    }

    /**
     * Loads whatever dataset the dropdown points at. Swings recorded into the outgoing one
     * are flushed first, so switching datasets never silently drops what was just taught.
     */
    private void applyNeuroDataset() {
        String wanted = neuroDataset.getValue();
        if (wanted.equals(NeuroDataset.INSTANCE.activeName()) || wanted.equals(requestedNeuroDataset)) {
            return;
        }

        requestedNeuroDataset = wanted;
        NeuroDataset.INSTANCE.saveIfDirty();
        if (!NeuroDataset.INSTANCE.load(wanted)) {
            ChatUtil.error("Neuro dataset not found  •  " + wanted);
        }
    }

    /** Points the dropdown at {@code name}; used by the {@code .neuro} command so both agree. */
    public void selectNeuroDataset(String name) {
        neuroDataset.updateModes(NeuroDataset.INSTANCE.names());
        if (neuroDataset.containsMode(name)) {
            neuroDataset.setValue(name);
        }
        requestedNeuroDataset = name;
    }

    public NeuroRotation neuroRotation() {
        return this.neuroRotation;
    }

    /**
     * ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ (Javelin-style)
     * Aim at the predicted point when both players are gliding — javelin does this unconditionally
     * for as long as both players are gliding, with no close-range fallback to the real hitbox.
     */
    /**
     * Javelin-style elytra prediction: realPredict from PredictUtils
     * Вычисляет предикт для цели на элитрах
     */
    private Vec3 realPredict(LivingEntity target) {
        if (!predictElytra.getValue()) {
            return target.getEyePosition();
        }
        
        // Проверка что цель движется (как в Javelin)
        if (Math.hypot(target.getX() - target.xOld, target.getZ() - target.zOld) * 20.0D == 0 
            && (target.getY() - target.yOld) * 20.0D == 0) {
            return target.getEyePosition();
        }
        
        // Используем PredictUtils с нужным количеством тиков
        float ticks = predictMode.is("Легитный") ? 2.0F : 3.0F;
        return PredictUtils.predict(target, target.position(), ticks);
    }

    private Vec3 getEffectiveTargetEyePos(LocalPlayer player, LivingEntity target, boolean attackLikely) {
        // ElytraResolver owns the aim while it is running its approach/retreat legs — its retreat
        // point sits off to the side of the target, which the predict would fight against.
        ElytraResolverFeature resolver = ElytraResolverFeature.getEnabled();
        if (resolver != null && resolver.isDriving(target)) {
            return resolver.getAimPoint();
        }
        
        // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: если оба планируют, используем наш предикт
        if (player.isFallFlying() && target.isFallFlying() && predictElytra.getValue()) {
            return realPredict(target);
        }
        
        return target.getEyePosition();
    }

    /**
     * Legit Snap's own hold on the swing, kept out of {@link #canAttack()} on purpose.
     *
     * <p>{@code canAttack()} is also what {@link #updateRotation()} reads as {@code attackLikely} to
     * decide how hard to pull. Folding this gate in there would mean the snap only pulls hard once
     * it is already on the target, which is precisely backwards — so the hold lives at the call site
     * and {@code attackLikely} keeps meaning "the swing is otherwise ready".
     *
     * <p>The mode swung with no rotation at all before this existed: nothing anywhere refused a hit
     * while the aim was still the player's own, so every FOV setting produced the same aimbot with a
     * decorative circle over it. Now the hit waits for the snap to have engaged and for the rotation
     * the server already holds to point into the hitbox.
     */
    private boolean legitSnapAllowsHit(LocalPlayer player) {
        if (!componentMode.is("Legit Snap")) return true;
        // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: скип рейтрейса для элитр
        if (target != null && player.isFallFlying() && target.isFallFlying()) return true;
        return legitSnapRotation.isEngaged() && rayTrace();
    }

    private void updateAttack() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || target == null || mc.gameMode == null) return;

        if (checkReturn(player)) return;

        boolean canStartSprint = player.input.keyPresses.forward()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.isFallFlying()
                && !player.isUsingItem()
                && !player.horizontalCollision
                && player.getFoodData().getFoodLevel() > 6
                && !player.isShiftKeyDown();

        boolean sprintStop = false;

        if (combatSettings.isSelected("Unblock Shield") && player.isBlocking()) {
            mc.gameMode.releaseUsingItem(player);
        }

        // Minced AttackAura.method750 skips its whole sprint block while gliding, and so do we now.
        // Forcing the packet path in the air added a STOP_SPRINTING that ground combat never sends
        // — a sprint transition mid-glide that no vanilla client produces. Vanilla cannot even
        // start a sprint while fall-flying, so there is nothing here worth a packet.
        if (player.isFallFlying()) {
            // Sprint latched from the run-up survives take-off, and vanilla voids the crit outright
            // while isSprinting() — that is why crits died when this block was emptied. Clearing the
            // flag alone is enough: LocalPlayer.sendIsSprintingIfNeeded() notices wasSprinting no
            // longer matches and emits the command itself, in vanilla's own place in the tick. That
            // is a transition a normal client makes; the hand-rolled packet I sent before was not.
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        } else if (sprintMode.is("Легитный")) {
            if (player.isSprinting()) {
                player.setSprinting(false);
                win.furryhook.utils.combat.SprintManager.markAttackImminent();
                return;
            }
        } else if (sprintMode.is("Обычный")) {
            if (player.isSprinting()) {
                if (player.connection != null) {
                    player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                }
                player.setSprinting(false);
                sprintStop = true;
            }
        }

        if (bypassWalls.getValue() && combatSettings.isSelected("Through Walls") && !canAttackThroughWalls(target)) {
            punchThroughWalls(player);
        }

        cpsLimit = System.currentTimeMillis() + (long) attackDelay.getValue().doubleValue();

        if (target instanceof Player targetPlayer && combatSettings.isSelected("Break Shield")) {
            breakShieldPlayer(targetPlayer);
        }

        // The mace takes the hit instead of the sword when — and only when — the smash would
        // actually apply. It returns true having already swung, so the ordinary attack is skipped:
        // swinging twice would burn the cooldown and land the second one on an empty bar.
        if (!smashWithMace(target)) {
            attackEntity(target);
        }
        notifyRotationAttack();

        win.furryhook.utils.combat.SprintManager.reset();
        win.furryhook.feature.impl.movement.SprintFeature sprintFeature = win.furryhook.feature.impl.movement.SprintFeature.getEnabled();
        if (sprintFeature != null && sprintFeature.keepSprint.getValue()) {
            player.setSprinting(true);
        }

        if (sprintMode.is("Обычный") && sprintStop && canStartSprint) {
            if (player.connection != null) {
                player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
            }
            player.setSprinting(true);
        }
    }

    private void punchThroughWalls(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || target == null) return;

        Vec3 startVec = player.getEyePosition();
        Vec3 targetPos = target.getEyePosition();
        Vec3 direction = targetPos.subtract(startVec);
        double distance = direction.length();
        if (distance < 1.0E-3) return;

        Vec3 normalizedDir = direction.normalize();
        for (double i = 0.0D; i < distance; i += 0.5D) {
            Vec3 point = startVec.add(normalizedDir.scale(i));
            BlockPos pos = BlockPos.containing(point);
            if (!mc.level.getBlockState(pos).isAir() && player.connection != null) {
                player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
            }
        }
    }

    /**
     * Hits a blocking player with an axe so their shield is disabled.
     *
     * <p>The shield is broken by the server, off the weapon component of whatever stack the
     * attack packet arrives holding, so the switch has to be a real one the server hears
     * about before the hit. That is the whole fix: the switch used to be made client-side
     * only and left to the vanilla lazy sync, which compares against the client's own idea
     * of the last slot it sent — a counter other features (AutoTool's silent switch) write
     * around, so the packet could be skipped and the axe hit would land with whatever was
     * held before.
     *
     * <p>The axe hit is followed by the ordinary weapon hit on the same tick. The server's
     * per-player attack cooldown resets between the two, so the sword swing lands with a
     * near-empty cooldown bar and does reduced damage — that is the price of guaranteeing
     * the shield goes down first. Fine for aura: shield disabled + weak follow-up beats no
     * follow-up at all, and the next tick's hit comes in fresh.
     *
     * @return true when the axe hit went out
     */
    private boolean breakShieldPlayer(Player entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || player.connection == null) return false;
        if (!isRaisingShield(entity)) return false;

        int selectedSlot = player.getInventory().getSelectedSlot();
        if (isAxe(player.getInventory().getItem(selectedSlot))) {
            // Already holding one: the ordinary attack breaks the shield by itself.
            return false;
        }

        int axeHotbarSlot = findHotbarAxe(player);
        int swappedMenuSlot = -1;
        if (axeHotbarSlot == -1) {
            int menuSlot = InventoryUtil.findInventorySlot(player, AuraFeature::isAxe);
            if (menuSlot == -1 || !InventoryUtil.swapWithHotbar(menuSlot, selectedSlot)) {
                return false;
            }
            // The swap put the axe in the held slot and the held item where the axe was.
            swappedMenuSlot = menuSlot;
            axeHotbarSlot = selectedSlot;
        }

        selectHotbarSlot(mc, player, axeHotbarSlot);
        attackEntity(entity);
        selectHotbarSlot(mc, player, selectedSlot);

        if (swappedMenuSlot != -1) {
            InventoryUtil.swapWithHotbar(swappedMenuSlot, selectedSlot);
        }
        return true;
    }

    /**
     * Swaps a mace in, lands the smash with it, and swaps straight back — the whole thing inside the
     * tick, the same shape {@link #breakShieldPlayer(Player)} uses, so the sword is back in hand
     * before the next swing is even considered.
     *
     * <p>The mace does not have to be on the hotbar. When it is not, it is swapped into the held
     * slot from the inventory and the displaced item is put back afterwards, which is why the swap
     * back is unconditional rather than best-effort.
     *
     * <p>Two conditions have to hold together, and both matter:
     *
     * <ul>
     *   <li><b>{@link MaceItem#canSmashAttack(LivingEntity)}</b> — vanilla's own answer. Below its
     *       fall-distance floor the mace does no bonus damage at all, so swapping there would be a
     *       straight downgrade from the sword for nothing.</li>
     *   <li><b>Height over the target</b>, {@link #maceHeight}. Standing on a trapdoor above someone
     *       and dropping on them is the shape this is for; the fall check alone would also fire on
     *       any ordinary jump-down that happens to pass a target.</li>
     * </ul>
     *
     * @return true when the mace hit went out and the caller must not attack again
     */
    private boolean smashWithMace(Entity entity) {
        if (!maceSwap.getValue()) return false;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || player.connection == null) return false;

        if (!MaceItem.canSmashAttack(player)) return false;
        if (player.getY() - entity.getY() < maceHeight.getValue()) return false;

        int selectedSlot = player.getInventory().getSelectedSlot();
        if (isMace(player.getInventory().getItem(selectedSlot))) {
            // Already holding mace and can smash - just attack with it
            attackEntity(entity);
            return true; // Tell caller we already attacked
        }

        int maceHotbarSlot = findHotbarMace(player);
        int swappedMenuSlot = -1;
        if (maceHotbarSlot == -1) {
            int menuSlot = InventoryUtil.findInventorySlot(player, AuraFeature::isMace);
            if (menuSlot == -1 || !InventoryUtil.swapWithHotbar(menuSlot, selectedSlot)) {
                return false;
            }
            // The swap put the mace in the held slot and the held item where the mace was.
            swappedMenuSlot = menuSlot;
            maceHotbarSlot = selectedSlot;
        }

        selectHotbarSlot(mc, player, maceHotbarSlot);
        attackEntity(entity);
        selectHotbarSlot(mc, player, selectedSlot);

        if (swappedMenuSlot != -1) {
            InventoryUtil.swapWithHotbar(swappedMenuSlot, selectedSlot);
        }
        return true;
    }

    private int findHotbarMace(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (isMace(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isMace(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.MACE);
    }

    /**
     * Switches the held slot and tells the server immediately instead of leaving it to the
     * once-a-tick sync, which a switch undone inside the same tick would slip past.
     * Vanilla's counter is moved along with it so it does not follow up with a stale slot.
     */
    private void selectHotbarSlot(Minecraft mc, LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        if (mc.gameMode instanceof MultiPlayerGameModeAccessor accessor) {
            accessor.setCarriedIndex(slot);
        }
    }

    /**
     * True while the target has a shield up. {@link LivingEntity#isBlocking()} only turns
     * true once the shield's block delay has passed, so a shield raised a tick ago is
     * caught by the raised-item check instead — that is precisely the window in which the
     * hit needs the axe.
     */
    private static boolean isRaisingShield(Player entity) {
        return entity.isBlocking() || (entity.isUsingItem() && entity.getUseItem().is(Items.SHIELD));
    }

    private int findHotbarAxe(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (isAxe(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isAxe(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.AXES);
    }

    private boolean checkReturn(LocalPlayer player) {
        return combatSettings.isSelected("Don't Hit While Eating") && player.isUsingItem() && !player.isBlocking();
    }

    /**
     * Lets the active rotation react to the hit it just landed. Rotations are ticked after
     * the attack in the same tick, so a reaction started here (Furry's 360) leaves with the
     * very next rotation packet.
     */
    private void notifyRotationAttack() {
        AuraRotation rotation = currentRotation();
        if (rotation == furryRotation) {
            configureFurryRotation();
        } else if (rotation == neuroRotation) {
            configureNeuroRotation();
        }
        rotation.onAttack();
    }

    private void attackEntity(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return;

        AirStuckFeature airStuck = FeatureManager.INSTANCE.getEnabled(AirStuckFeature.class);
        if (airStuck != null) {
            airStuck.pauseForAttack();
        }

        // Досылает угол этого тика и берёт удар на учёт до подтверждения урона. Пакет удара
        // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: синхро ротации перед ударом (опционально)
        // ElytraTargetFeature.beforeAttack(player, entity);

        ensureCriticalWhileGliding(player);

        mc.gameMode.attack(player, entity);
        player.swing(InteractionHand.MAIN_HAND);

        // PounchClient AttackAura:1950 — a landed hit while gliding flips the resolver straight
        // into its retreat leg instead of leaving us parked in front of the target.
        ElytraResolverFeature resolver = ElytraResolverFeature.getEnabled();
        if (resolver != null && player.isFallFlying() && entity instanceof LivingEntity living) {
            resolver.onHit(living);
        }

        strikeLightningTargetEsp(player, entity);
        if (usesSoulsTargetEsp()) {
            SoulImpulse.hit(entity);
        }
    }

    /**
     * Vanilla counts a crit on {@code fallDistance > 0 && !onGround && !isSprinting} (plus water /
     * climbable / passenger / mobility guards) — there is no fall-flying clause, so a crit in the
     * air is legal. What is missing while climbing under rocket thrust is fall distance: the server
     * only accrues it on descent, so a swing taken on the way up is always flat.
     *
     * <p>Two position packets, up then back, hand the server that descent without moving the player
     * — it books the 1/16 of a block as fall distance and the swing that follows lands as a crit.
     * Sprint is already dropped for the hit in {@link #updateAttack()}.
     */
    private void ensureCriticalWhileGliding(LocalPlayer player) {
        if (!packetCrits.getValue()) return;
        if (!player.isFallFlying() || player.connection == null) return;
        if (player.fallDistance > 0.0D) return;

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + CRIT_HOP, z, false, false));
        player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, false));
    }

    /**
     * Target ESP's "Молнии" mode: instead of a marker/ghost/circle, every landed
     * hit calls down a bolt from the same shader-driven strike the Atmosphere
     * module draws for weather lightning, aimed at the entity just hit.
     */
    private void strikeLightningTargetEsp(LocalPlayer player, Entity entity) {
        if (!usesLightningTargetEsp()) return;

        double bbHeight = entity instanceof LivingEntity living ? living.getBbHeight() : 1.8D;
        Vec3 impact = entity.position().add(0.0D, bbHeight * 0.5D, 0.0D);
        StormState.INSTANCE.strikeAt(impact, player.distanceTo(entity));
    }

    public boolean canAttack() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || target == null || checkReturn(player)) return false;

        // Reach. On the ground this is the aura's own, unchanged. In the air ElytraTarget owns it,
        // because javelin's AttackAura measures an elytra fight from the *predicted* point rather
        // than the hitbox: a player crossing at two blocks a tick is never where its box says it is
        // ============ INTEGRATED ELYTRA TARGET LOGIC (Javelin-style) ============
        // Проверка дистанции для элитр: var dist = 3.2F из Javelin
        double distance = getStrictDistance(target);
        
        // Если оба планируют - проверяем дистанцию до ПРЕДИКТА (как в Javelin)
        if (player.isFallFlying() && target.isFallFlying() && predictElytra.getValue()) {
            double predictDist = player.getEyePosition().distanceTo(realPredict(target));
            if (predictDist > 3.2D) return false; // Javelin: var dist = 3.2F
        } 
        // Если только МЫ планируем - жёсткие 3 блока по хитбоксу (как в Javelin)
        else if (player.isFallFlying()) {
            double hitboxDist = player.getEyePosition().distanceTo(MultipointUtil.getNearestPoint(target, 0.0D));
            if (hitboxDist > 3.0D) return false; // Javelin: жёсткие 3
        }
        // Обычная проверка дистанции если не на элитрах
        else if (distance >= attackDistance()) {
            return false;
        }

        // Minced AttackAura.method785 drops only its *camera* raycast while the overtake owns a
        // gliding target — method750's ray from the rotation the server already has is never
        // skipped, because the server refuses any swing whose ray misses the hitbox. So the
        // overtake here releases the mode-specific holds (Furry's spin, Polar's tolerance cone,
        // BMW's view cone) and nothing else.
        boolean elytraOvertake = player.isFallFlying() && target.isFallFlying();

        // Swinging with the ray off the target is the single loudest thing an aura can do — vanilla
        // does not care, every anticheat past it does. So the ray stays mandatory in the air too;
        // what buys back the fly-by window instead is the margin inside rayTrace().
        boolean rayOptional = false;

        // BMW is a legit no-spoof mode: only strike targets the player is actually
        // looking at, never behind the back or off to the side, out of view.
        if (!elytraOvertake && componentMode.is("BMW") && !isTargetInView(player, target)) return false;

        // Furry throws the aim off the target on purpose (360 after the hit, hitbox-wide
        // jitter), so the hit is held back until the rotation the server already has points
        // into the hitbox — that is what keeps both behaviours miss-free.
        if (componentMode.is("Furry")) {
            if (!elytraOvertake && furryRotation.isSpinning()) return false;
            if (!rayOptional && !rayTrace()) return false;
        }

        // Polar never snaps: the aim walks in under a per-tick cap and deliberately overshoots
        // on the way in. The hit waits for the rotation the server already has to be inside the
        // tolerance cone around the point last aimed at, and for the block ray to be clear.
        if (componentMode.is("Polar")) {
            if (!elytraOvertake && !polarRotation.isOnTarget(player)) return false;
            if (!rayOptional && !rayTrace()) return false;
        }

        // Spooky only strikes once the rotation the server already has actually points into the
        // hitbox AND the yaw has settled on the spot (plus a small human skip share). It rotates
        // first (see rotateFirst), so this sees this tick's angle — a swing never leaves while
        // the turn is still arriving, and never fires on a raw legal frame like a bot.
        if (componentMode.is("Spooky")) {
            if (!elytraOvertake && !spookyRotation.shouldStrike(player, target)) return false;
            if (!rayOptional && !rayTrace()) return false;
        }

        // Funtime rides the aim a little off the point on purpose and spends the first ticks
        // of an engagement still travelling, so the hit waits for the rotation the server
        // already has to point into the hitbox — that is what keeps the offset free.
        if (componentMode.is("Funtime") && !rayOptional && !rayTrace()) return false;

        // Funfick is Expensive's own gate, not an addition: the module tests
        // RaycastUtil.rayTrace against the rotation the tracker currently holds and returns before
        // attackEntity when it misses. Funtime's post-hit freeze and its six-to-twelve degree wobble
        // guarantee ticks where the aim is deliberately off the point, and every one of those would
        // otherwise be a swing into empty air.
        if (componentMode.is("Funfick") && !rayOptional && !rayTrace()) return false;

        // Neuro aims with the player's own learned motion, which is under no obligation to
        // sit on the hitbox every tick. While learning it is the player's real aim that
        // decides. Either way the hit waits for the rotation the server already has to
        // point into the target, so recorded swings end on hits the player truly landed.
        if (componentMode.is("Neuro") && !rayOptional && !rayTrace()) return false;

        if (cpsLimit > System.currentTimeMillis()) return false;

        // Anti-pet: at extended reach the hit must land on a fully charged swing, never a
        // dribbling tap that just plinks off armour. Below the threshold vanilla's own
        // 0.92 gate is enough to keep the CPS honest.
        float swing = player.getAttackStrengthScale(0.5F);
        boolean longReach = !elytraOvertake && distance >= noPetRange.getValue();
        boolean turbo = combatSettings.isSelected("Turbo Criticals");
        // Turbo fires at the earliest crit-eligible frame (vanilla's own > 0.9 gate for the
        // 1.5x multiplier), saving roughly one tick per hit versus the old 0.92 floor. At
        // long reach the swing still has to be near-perfect so the plink at 6 blocks is
        // gone whether Turbo is on or off.
        // Gliding fires on the earliest frame vanilla still pays the 1.5x for (its own gate is
        // scale > 0.9). A fly-by window is two ticks wide — waiting for 0.92 throws away whole
        // passes, and the crit multiplier is identical either side of that margin.
        float minSwing = longReach ? 0.99F : (player.isFallFlying() ? 0.902F : (turbo ? 0.905F : 0.92F));
        boolean swingReady = swing >= minSwing;

        if (mc.level != null && AuraUtil.isJumpBlockedByCeiling(player, mc.level)) {
            // Locked under a ceiling: crits are impossible, take the hit anyway but still
            // hold to the anti-pet gate so a max-range plink never leaves the client.
            return swingReady;
        }

        if (!swingReady) return false;

        boolean onlyCrits = combatSettings.isSelected("Only Criticals") || combatSettings.isSelected("Turbo Criticals");
        if (!onlyCrits) return true;

        // Gliding used to be an unconditional yes here, on the theory that a fly-by window is two
        // ticks wide and waiting for a descent frame spends it. That was true of the old overtake,
        // which arrived at full speed and got one tick or none. ElytraTarget now brakes into the
        // pass and cuts thrust for it, so the window is several ticks and every one of them is a
        // descent — the crit condition is simply true for the whole pass. With "Только криты" on it
        // holds the rare tick that is not, instead of spending it on a flat hit.
        if (player.isFallFlying()) return true;

        if (mc.level == null || !AuraUtil.isValidAttackCondition(player, mc.level)) return true;

        // Turbo Criticals: fire on the descent frame the moment the swing is charged,
        // no wait for Smart Criticals' on-ground safety window. Land a real crit every
        // cycle instead of dropping to a flat hit whenever the arc gets ugly.
        if (combatSettings.isSelected("Turbo Criticals")) {
            if (player.fallDistance > 0.0F && !player.onGround()) return true;
            // On ground with a charged swing and no crit lined up — AutoJump will pop the
            // next tick, so skip this window and let the crit land instead of the pet.
            if (!longReach && combatSettings.isSelected("Smart Criticals")
                    && !mc.options.keyJump.isDown()
                    && player.onGround()) {
                return true;
            }
            if (player.isFallFlying()) return true;
            return false;
        }

        if (combatSettings.isSelected("Smart Criticals")
                && player.fallDistance <= 0.0F
                && !mc.options.keyJump.isDown()
                && player.onGround()) {
            // Smart Criticals used to allow a flat hit at long range too — that is exactly
            // the "petting" the caller wants gone at 6 blocks. Force a real crit out there.
            if (longReach) return false;
            return true;
        }

        if (player.isFallFlying()) return true;

        return AuraUtil.canCritical(player);
    }

    /**
     * True when the target's eye lies inside the player's real view cone.
     * Uses the player's actual look direction (BMW never spoofs it) and gates on
     * the game's own horizontal FOV, so a target behind the back or beyond the
     * screen edges is rejected.
     */
    private boolean isTargetInView(LocalPlayer player, LivingEntity target) {
        Minecraft mc = Minecraft.getInstance();

        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 toTarget = target.getEyePosition().subtract(player.getEyePosition());
        if (toTarget.lengthSqr() < 1.0E-6D) return true;
        toTarget = toTarget.normalize();

        double dot = Math.max(-1.0D, Math.min(1.0D, look.dot(toTarget)));
        double angle = Math.toDegrees(Math.acos(dot));

        double verticalFov = mc.options.fov().get();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        double aspect = height > 0 ? (double) width / (double) height : 16.0D / 9.0D;
        double horizontalFov = 2.0D * Math.toDegrees(
                Math.atan(Math.tan(Math.toRadians(verticalFov) * 0.5D) * aspect));

        return angle <= horizontalFov * 0.5D;
    }

    /**
     * Full 3D angle between the rotation currently being sent and the direction to the
     * entity's eyes, in degrees. Expensive compares the dot products directly and takes the
     * largest; the angle is the same ordering the other way round, and it reads as a number
     * anyone can sanity-check against the crosshair.
     */
    private double fovAngleTo(LocalPlayer player, LivingEntity entity) {
        float yaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float pitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        Vec3 look = Vec3.directionFromRotation(pitch, yaw).normalize();
        Vec3 toTarget = entity.getEyePosition().subtract(player.getEyePosition());
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }

        double dot = Math.max(-1.0D, Math.min(1.0D, look.dot(toTarget.normalize())));
        return Math.toDegrees(Math.acos(dot));
    }

    private LivingEntity findTarget() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;

        List<LivingEntity> potentialTargets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                potentialTargets.add(living);
            }
        }

        if (potentialTargets.isEmpty()) {
            return null;
        }

        if (potentialTargets.size() > 1) {
            switch (sortMode.getValue()) {
                case "По здоровью" -> potentialTargets.sort(Comparator.comparingDouble(LivingEntity::getHealth));
                case "По дистанции" -> potentialTargets.sort(Comparator.comparingDouble(player::distanceTo));
                case "По броне" -> potentialTargets.sort(Comparator.comparingDouble(entity -> entity instanceof Player p ? getEntityArmor(p) : entity.getArmorValue()));
                // Expensive AttackAura BY_FOV: order by the angle between the look vector we are
                // actually sending and the direction to the entity, smallest first. Taken off the
                // server rotation while a rotation is active, so the sort reads the head the
                // server sees rather than the free-look camera.
                case "По FOV" -> potentialTargets.sort(Comparator.comparingDouble(entity -> fovAngleTo(player, entity)));
            }

            // javelin TargetFinder.elytraHighPriority — "Сперва элитры" outranks the sort above.
            // The test is whether the chest slot *holds* an elytra, not whether the target happens
            // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: приоритет целям с элитрой
            // Javelin: glidingFirst -> elytraHighPriority
            if (player.isFallFlying()) {
                potentialTargets.sort(Comparator.comparing((LivingEntity entity) -> {
                    ItemStack chest = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
                    return !chest.is(Items.ELYTRA);
                }));
            }
        }

        return potentialTargets.get(0);
    }

    private boolean isValidTarget(LivingEntity entity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || entity == player) return false;
        if (entity.tickCount < 3) return false;
        // AlphaDLC AttackAura.isValidTarget(entity, sticky): the target already being fought keeps
        // an extra 0.75 blocks of validity, so a player hovering on the range boundary is not
        // dropped and re-acquired every other tick — that churn is what makes an aura stutter.
        double stickyBonus = entity == this.target ? STICKY_RANGE_BONUS : 0.0D;
        if (getStrictDistance(entity) >= getMaxAimRange() + stickyBonus) return false;
        if (AntiBotFeature.checkBot(entity)) return false;
        if (entity instanceof ArmorStand) return false;
        if (entity.isInvulnerable() || !entity.isAlive()) return false;

        if (entity instanceof Player playerEntity) {
            if (!targets.isSelected("Игроки")) return false;
            if (playerEntity.getGameProfile().name().equalsIgnoreCase(player.getGameProfile().name())) return false;
            if (!targets.isSelected("Друзья") && FriendManager.INSTANCE.isFriend(playerEntity.getGameProfile().name())) {
                return false;
            }

            boolean naked = playerEntity.getArmorValue() == 0;
            boolean invisible = playerEntity.isInvisible();

            if (invisible && naked) {
                return targets.isSelected("Голые невидимки");
            }
            if (invisible) {
                return targets.isSelected("Невидимки");
            }
            if (naked) {
                return targets.isSelected("Голые");
            }
            return true;
        }

        if (entity instanceof Monster || entity instanceof Phantom || entity instanceof Bat
                || entity instanceof Shulker || entity instanceof Villager
                || entity instanceof Animal || entity instanceof AmbientCreature) {
            return targets.isSelected("Мобы");
        }

        return true;
    }

    public double getEntityArmor(Player targetPlayer) {
        return targetPlayer.getArmorValue();
    }

    private boolean canAttackThroughWalls(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || entity == null) return false;

        Vec3 targetVec = entity.position().add(0.0D, entity.getEyeHeight(), 0.0D);
        Vec3 playerVec = player.getEyePosition();

        var result = mc.level.clip(new net.minecraft.world.level.ClipContext(
                playerVec, targetVec,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player
        ));

        return result.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    public boolean rayTrace() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || target == null) return false;

        Vec3 eye = player.getEyePosition();
        // Checked against the rotation this tick is about to send, not the previously sent one.
        //
        // Gating on the last *sent* angle (Minced's method836, AlphaDLC's getLastYaw) is a tick
        // behind, and against a target that moves a hitbox per tick it rejects nearly every swing —
        // it was what killed the hit rate here. It also was not what caused the flagging: that was
        // the firework thrust redirect, and with the thrust back to vanilla this is clean.
        //
        // RotationContext writes the spoofed angle straight into the player and vanilla's own
        // sendPosition ships it, so this is the angle the server ends up holding either way.
        float serverYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float serverPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();
        Vec3 look = Vec3.directionFromRotation(serverPitch, serverYaw);
        Vec3 end = eye.add(look.scale(attackDistance()));

        // AlphaDLC RANGE_MARGIN — the box is grown by a sixth of a block before the ray is tested.
        // Server hitboxes lag a tick behind a moving target, so an exact-box test rejects rays the
        // server would have accepted; the margin covers that without ever aiming off the player.
        AABB box = target.getBoundingBox().inflate(RANGE_MARGIN);
        if (box.contains(eye) || box.clip(eye, end).isPresent()) return true;

        // javelin AttackHandler.canAttack: while both players are gliding the ray is tested against
        // the box the target sweeps between where it is and where the predict says it will be, not
        // against the box alone. Two players closing head-on cover four blocks between the tick the
        // ИНТЕГРИРОВАННЫЙ ЭЛИТРА-ТАРГЕТ: заметённая коробка для элитр (Javelin-style)
        // Коробка от текущей позиции до предикта - луч проверяется по ней
        AABB sweep = null;
        if (player.isFallFlying() && target.isFallFlying() && predictElytra.getValue()) {
            AABB current = target.getBoundingBox();
            Vec3 predictPos = realPredict(target);
            AABB predicted = current.move(predictPos.subtract(target.position()));
            sweep = current.minmax(predicted);
        }
        
        if (sweep != null) {
            AABB swept = sweep.inflate(RANGE_MARGIN);
            // Заметённая коробка живёт дальше обычного реча — луч длиной в attackDistance()
            // до неё просто не достаёт, и проверка отказывала бы в ударе, который сервер
            // уже разрешил. Луч тянется до дальнего угла этой коробки.
            double sweepReach = Math.sqrt(swept.distanceToSqr(eye)) + swept.getSize() + attackDistance();
            Vec3 sweptEnd = eye.add(look.scale(sweepReach));
            return swept.contains(eye) || swept.clip(eye, sweptEnd).isPresent();
        }
        return false;
    }

    /**
     * How far a target stays <b>valid</b> — which is not how far it can be hit.
     *
     * <p>While gliding this opens out to ElytraTarget's chase distance, so the aura holds the
     * player it is flying at from thirty blocks away and points the head at them the whole approach.
     * Hitting is gated separately and strictly: {@link #canAttack()} refuses anything at or beyond
     * {@link #attackDistance()}, so a target held at range is aimed at and never swung at.
     */
    public double getMaxAimRange() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.isFallFlying() && predictElytra.getValue()) {
            return Math.max(attackDistance() + rotateDistance.getValue(), 60.0D);
        }
        return attackDistance() + rotateDistance.getValue();
    }

    public double attackDistance() {
        double base = attackRange.getValue();
        AirStuckFeature airStuck = FeatureManager.INSTANCE.getEnabled(AirStuckFeature.class);
        if (airStuck != null && airStuck.isExtendedKillAuraRange()) {
            return Math.max(base, airStuck.getExtendedKillAuraRange());
        }
        return base;
    }

    public double getStrictDistance(Entity entity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return 0.0D;
        if (entity instanceof LivingEntity living) {
            return AuraUtil.getDistance(player, living);
        }
        return player.distanceTo(entity);
    }

    private void reset() {
        target = null;
        predictedElytraPos = null;
        elytraTarget = null;
        resetRotationState();
        RotationContext.disengage();
        cpsLimit = System.currentTimeMillis();
    }

    private static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    public static double direction(float rotationYaw, double moveForward, double moveStrafing) {
        if (moveForward < 0.0D) rotationYaw += 180.0F;

        float forward = 1.0F;
        if (moveForward < 0.0D) forward = -0.5F;
        else if (moveForward > 0.0D) forward = 0.5F;

        if (moveStrafing > 0.0D) rotationYaw -= 90.0F * forward;
        if (moveStrafing < 0.0D) rotationYaw += 90.0F * forward;

        return Math.toRadians(rotationYaw);
    }

    /**
     * Rewrites the movement keys so the player travels along {@code intentYaw} even though the
     * game resolves them against the spoofed rotation. The eight key combinations are the only
     * directions on offer, so the closest one to the intent wins.
     */
    private void fixMovement(PlayerInputEvent event, float intentYaw) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        Vec2 moveVec = event.getMoveVector();
        float forward = moveVec.y;
        float strafe = moveVec.x;

        if (forward == 0.0F && strafe == 0.0F) return;

        double angle = wrapDegrees((float) Math.toDegrees(direction(intentYaw, forward, strafe)));
        float serverYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();

        float closestForward = 0.0F, closestStrafe = 0.0F;
        double closestDifference = Double.MAX_VALUE;

        for (float predictedForward = -1.0F; predictedForward <= 1.0F; predictedForward += 1.0F) {
            for (float predictedStrafe = -1.0F; predictedStrafe <= 1.0F; predictedStrafe += 1.0F) {
                if (predictedStrafe == 0.0F && predictedForward == 0.0F) continue;
                double predictedAngle = wrapDegrees((float) Math.toDegrees(direction(serverYaw, predictedForward, predictedStrafe)));
                // Wrapped: intent and spoofed rotation are independent now, so the pair straddles
                // the ±180 seam regularly, and a raw subtraction there reads 358° apart as the
                // worst match when it is the best one.
                double difference = Math.abs(wrapDegrees((float) (angle - predictedAngle)));
                if (difference < closestDifference) {
                    closestDifference = difference;
                    closestForward = predictedForward;
                    closestStrafe = predictedStrafe;
                }
            }
        }

        event.setMoveVector(new Vec2(closestStrafe, closestForward));

        boolean fwd = closestForward > 0.0F;
        boolean bwd = closestForward < 0.0F;
        boolean left = closestStrafe > 0.0F;
        boolean right = closestStrafe < 0.0F;
        net.minecraft.world.entity.player.Input in = event.getKeyPresses();
        event.setKeyPresses(new net.minecraft.world.entity.player.Input(
                fwd, bwd, left, right,
                in.jump(), in.shift(), in.sprint()
        ));
    }

    private void moveToPosition(PlayerInputEvent event, Vec3 position, float currentYaw) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        Vec2 input = event.getMoveVector();
        if (input.lengthSquared() <= 1.0E-6F) {
            return;
        }

        double deltaX = position.x - player.getX();
        double deltaZ = position.z - player.getZ();

        double angleToTarget = Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
        angleToTarget = wrapDegrees((float) angleToTarget);

        float bestForward = 0.0F;
        float bestStrafe = 0.0F;
        double minDifference = Double.MAX_VALUE;

        for (float forward = -1.0F; forward <= 1.0F; forward += 1.0F) {
            for (float strafe = -1.0F; strafe <= 1.0F; strafe += 1.0F) {
                if (forward == 0.0F && strafe == 0.0F) continue;

                double moveAngle = Math.toDegrees(direction(currentYaw, forward, strafe));
                double difference = Math.abs(wrapDegrees((float) (angleToTarget - moveAngle)));

                if (difference < minDifference) {
                    minDifference = difference;
                    bestForward = forward;
                    bestStrafe = strafe;
                }
            }
        }

        event.setMoveVector(new Vec2(bestStrafe, bestForward));

        boolean fwd = bestForward > 0.0F;
        boolean bwd = bestForward < 0.0F;
        boolean left = bestStrafe > 0.0F;
        boolean right = bestStrafe < 0.0F;
        net.minecraft.world.entity.player.Input in = event.getKeyPresses();
        event.setKeyPresses(new net.minecraft.world.entity.player.Input(
                fwd, bwd, left, right,
                in.jump(), in.shift(), in.sprint()
        ));
    }
}
