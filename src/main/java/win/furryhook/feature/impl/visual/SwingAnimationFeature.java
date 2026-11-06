package win.furryhook.feature.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Vector3f;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class SwingAnimationFeature extends Feature {
    public enum Style {
        NONE,
        SMOOTH,
        SWIPE,
        GSMAR,
        TRANSLATE,
        JUMP_TRANSLATE,
        SMOOTH_BLOCK,
        SMOOTH_OLD,
        DEFAULT,
        BIG,
        FAP
    }

    public final ModeSetting mode = register(new ModeSetting("Mode", "Smooth",
            "None", "Smooth", "Swipe", "GSmar",
            "Translate", "Jump Translate", "Smooth Block", "Smooth Old", "Default", "Big", "Fap"));
    public final NumberSetting strength = register(new NumberSetting("Strength", 1.0, 0.5, 3.0, 0.1, ""));
    public final NumberSetting duration = register(new NumberSetting("Duration", 1.0, 0.5, 4.0, 0.1, ""));
    public final NumberSetting angle = register(new NumberSetting("Angle", 40.0, 0.0, 70.0, 1.0, "°")
            .visibleWhen(() -> style() == Style.TRANSLATE || style() == Style.SMOOTH_BLOCK));
    public final BooleanSetting onlySwinging = register(new BooleanSetting("Only Swinging", false));
    public final BooleanSetting onlyAura = register(new BooleanSetting("Only Aura", false));

    public final BooleanSetting spin360 = register(new BooleanSetting("Spin 360", false));
    public final ModeSetting spinHand = register(new ModeSetting("Spin Hand", "Right Arm",
            "Both Arms", "Right Arm", "Left Arm").visibleWhen(spin360::getValue));
    public final NumberSetting spinSpeed = register(new NumberSetting("Spin Speed", 2.0, 1.0, 5.0, 1.0, "")
            .visibleWhen(spin360::getValue));

    public SwingAnimationFeature() {
        super("SwingAnimation", "Custom first person swing animation", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public Style style() {
        return switch (mode.getValue()) {
            case "None" -> Style.NONE;
            case "Smooth" -> Style.SMOOTH;
            case "Swipe" -> Style.SWIPE;
            case "GSmar" -> Style.GSMAR;
            case "Translate" -> Style.TRANSLATE;
            case "Jump Translate" -> Style.JUMP_TRANSLATE;
            case "Smooth Block" -> Style.SMOOTH_BLOCK;
            case "Smooth Old" -> Style.SMOOTH_OLD;
            case "Default" -> Style.DEFAULT;
            case "Big" -> Style.BIG;
            case "Fap" -> Style.FAP;
            default -> Style.NONE;
        };
    }

    private boolean swingLatched;

    public boolean shouldApply(float swingProgress) {
        boolean progressing = swingProgress > 0.001F;
        if (!isEnabled() || style() == Style.NONE) {
            swingLatched = false;
            return false;
        }
        if (!progressing) {
            // Swing has fully returned to rest: clear the latch so the next swing re-checks the gate fresh.
            swingLatched = false;
        }
        if (onlySwinging.getValue() && !progressing) {
            return false;
        }
        if (!onlyAura.getValue() || isAuraTargeting()) {
            swingLatched = progressing;
            return true;
        }
        /*
         * The gate just failed mid-frame (e.g. the aura target died or got knocked out of range
         * from the hit that's still playing out). Falling back to the vanilla pose here would snap
         * the item from a big custom offset straight to vanilla's own resting transform mid-swing -
         * visible as the item "dropping" on modes whose baseline differs a lot from vanilla's (Translate,
         * Smooth Block, Default, Big, Fap...). Keep the custom pose until this swing finishes on its own.
         */
        return swingLatched && progressing;
    }

    public boolean shouldModifyDuration() {
        if (!isEnabled()) {
            return false;
        }
        return onlyAura.getValue() ? isAuraTargeting() : true;
    }

    public float durationMultiplier() {
        return duration.getValue().floatValue();
    }

    public boolean spinAppliesTo(HumanoidArm arm) {
        if (!isEnabled() || !spin360.getValue()) {
            return false;
        }
        return switch (spinHand.getValue()) {
            case "Both Arms" -> true;
            case "Left Arm" -> arm == HumanoidArm.LEFT;
            default -> arm == HumanoidArm.RIGHT;
        };
    }

    public void applySpin(PoseStack poseStack) {
        int divisor = Math.max(1, 6 - spinSpeed.getValue().intValue());
        float angleDeg = (System.currentTimeMillis() % (360L * divisor)) / (float) divisor;
        rotate(poseStack, angleDeg, -1.0F, 0.0F, 0.0F);
    }

    public void apply(PoseStack poseStack, HumanoidArm arm, float rawProgress, float equipProgress) {
        float progress = Mth.clamp(rawProgress, 0.0F, 1.0F);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float sin1 = Mth.sin(progress * progress * Mth.PI);
        float sin2 = Mth.sin(Mth.sqrt(progress) * Mth.PI);
        float power = strength.getValue().floatValue();
        float sVal = power * 70.0F;
        switch (style()) {
            case SWIPE -> {
                poseStack.translate(.56F * side, -.32F, -.72F);
                poseStack.mulPose(Axis.YP.rotationDegrees(70 * side));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-20 * side));
                poseStack.mulPose(Axis.YP.rotationDegrees(sin2 * sin1 * -5 * power));
                poseStack.mulPose(Axis.XP.rotationDegrees(sin2 * sin1 * -120 * power));
                poseStack.mulPose(Axis.XP.rotationDegrees(-70));
            }
            case GSMAR -> {
                float thrust = Mth.sin(progress * Mth.PI);
                float snap = Mth.sin(progress * Mth.TWO_PI) * thrust;
                poseStack.translate(side * .50F, -.34F + thrust * .08F * power,
                        -.62F - thrust * .34F * power);
                poseStack.mulPose(Axis.YP.rotationDegrees(side * (38.0F - thrust * 10.0F * power)));
                poseStack.mulPose(Axis.ZP.rotationDegrees(side * (-13.0F + snap * 8.0F * power)));
                poseStack.mulPose(Axis.XP.rotationDegrees(-68.0F + snap * 34.0F * power));
                poseStack.translate(0.0F, -.08F, thrust * .10F * power);
            }
            case SMOOTH -> {
                poseStack.translate(side * .56F, -.42F, -.72F);
                poseStack.mulPose(Axis.YP.rotationDegrees(side * (45 + sin1 * -20 * power)));
                poseStack.mulPose(Axis.ZP.rotationDegrees(side * sin2 * -20 * power));
                poseStack.mulPose(Axis.XP.rotationDegrees(sin2 * -80 * power));
                poseStack.mulPose(Axis.YP.rotationDegrees(side * -45));
                poseStack.translate(0, -.1, 0);
            }
            case TRANSLATE -> {
                poseStack.translate(0.6F, 0.0F, -0.8F);
                poseStack.translate(0.56F, -1.0F, -0.82F);
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-60.0F));
                float translateAngle = -angle.getValue().floatValue()
                        - 40.0F * Mth.sin(progress * Mth.PI) * sVal / 50.0F;
                rotate(poseStack, translateAngle, 1.0F, 0.0F, 0.0F);
                rotate(poseStack, -20.0F, 0.01F, 0.0F, 0.0F);
                poseStack.translate(-0.5F, 0.08F, 0.0F);
            }
            case JUMP_TRANSLATE -> {
                float offset = -0.2F * Mth.sin(progress * Mth.PI);
                float phase = Mth.sqrt(Math.max(0.0F, -offset)) * Mth.PI;
                poseStack.translate(0.4F, -0.15F, -0.5F);
                poseStack.translate(0.0F, offset * -sVal / 100.0F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
                rotate(poseStack, Mth.sin(offset * offset * Mth.PI) * -34.0F, 0.0F, 1.0F, 0.2F);
                rotate(poseStack, Mth.sin(phase) * -20.7F, 0.2F, 0.1F, 1.0F);
                rotate(poseStack, Mth.sin(phase) * -68.6F, 1.3F, 0.1F, 0.2F);
                poseStack.scale(0.4F, 0.4F, 0.4F);
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(60.0F));
            }
            case SMOOTH_BLOCK -> {
                poseStack.translate(0.6F, 0.0F, -0.8F);
                poseStack.translate(0.56F, -1.0F, -0.82F);
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-60.0F));
                float blockAngle = -angle.getValue().floatValue()
                        - 40.0F * Mth.sin(progress * Mth.PI) * sVal / 50.0F;
                rotate(poseStack, blockAngle, 1.0F, equipProgress, 0.0F);
                rotate(poseStack, -40.0F, 0.01F, 0.0F, 0.0F);
                poseStack.translate(-0.5F, 0.08F, 0.0F);
            }
            case SMOOTH_OLD -> {
                poseStack.translate(0.4F, 0.0F, -0.8F);
                poseStack.translate(0.56F, -0.44F, -0.71999997F);
                poseStack.mulPose(Axis.YP.rotationDegrees(72.0F));
                rotate(poseStack, sin2 * -10.0F, 0.0F, 0.0F, 1.0F);
                rotate(poseStack, sin2 * -sVal, 0.01F, 0.0F, 0.0F);
                rotate(poseStack, -20.0F, 0.01F, 0.0F, 0.0F);
                poseStack.translate(-0.5F, 0.08F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(20.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(20.0F));
            }
            case DEFAULT -> {
                float angleDefault = -sVal * Mth.sin(progress * Mth.PI) * sVal / 100.0F;
                poseStack.translate(1.2F, -0.1F, -0.71999997F);
                poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
                poseStack.translate(-0.5F, 0.2F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(60.0F));
                poseStack.translate(0.3F, 0.0F, 0.4F);
                poseStack.translate(0.0F, 0.5F, 0.0F);
                rotate(poseStack, 90.0F, 1.0F, 0.0F, -1.0F);
                poseStack.translate(0.6F, 0.5F, 0.0F);
                rotate(poseStack, -90.0F, 1.0F, 0.0F, -1.0F);
                rotate(poseStack, -10.0F, 1.0F, 0.0F, -1.0F);
                rotate(poseStack, 10.0F, -1.0F, 0.0F, 0.0F);
                poseStack.translate(0.0F, 0.0F, -0.5F);
                rotate(poseStack, angleDefault, 1.0F, 0.0F, 1.0F);
                poseStack.translate(0.0F, 0.0F, 0.5F);
            }
            case BIG -> {
                poseStack.translate(0.5F, -0.3F, -0.65F);
                poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
                rotate(poseStack, sin1 * -20.0F, 0.0F, 1.0F, 0.0F);
                rotate(poseStack, sin2 * -20.0F, 0.0F, 0.0F, 1.0F);
                rotate(poseStack, sin2 * -sVal, 1.0F, 0.0F, 0.0F);
                poseStack.scale(0.5F, 0.5F, 0.5F);
                poseStack.translate(-0.5F, 0.2F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(60.0F));
            }
            case FAP -> {
                poseStack.translate(1.2F, -0.1F, -0.71999997F);
                poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
                poseStack.translate(-0.5F, 0.2F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(60.0F));
                long now = System.currentTimeMillis();
                long remainder = now % 255L;
                int pulse = (int) Math.min(255L, (remainder > 127L ? Math.abs(remainder - 255L) : remainder) * 2L);
                float legacySwingOffset = -0.2F * Mth.sin(progress * Mth.PI);
                float folded = legacySwingOffset > 0.5F ? 1.0F - legacySwingOffset : legacySwingOffset;
                poseStack.translate(0.3F, 0.0F, 0.4F);
                poseStack.translate(0.0F, 0.5F, 0.0F);
                rotate(poseStack, 90.0F, 1.0F, 0.0F, -1.0F);
                poseStack.translate(0.6F, 0.5F, 0.0F);
                rotate(poseStack, -90.0F, 1.0F, 0.0F, -1.0F);
                rotate(poseStack, -10.0F, 1.0F, 0.0F, -1.0F);
                rotate(poseStack, -folded * 10.0F, 10.0F, 10.0F, -9.0F);
                rotate(poseStack, 10.0F, -1.0F, 0.0F, 0.0F);
                poseStack.translate(0.0, 0.0, -0.5);
                boolean swinging = progress > 0.001F;
                float fapAngle = swinging ? -pulse / (110.0F - sVal) : 1.0F;
                rotate(poseStack, fapAngle, 1.0F, 0.0F, 1.0F);
                poseStack.translate(0.0, 0.0, 0.5);
            }
            default -> {
            }
        }
    }

    private static void rotate(PoseStack poseStack, float degrees, float axisX, float axisY, float axisZ) {
        float lengthSquared = axisX * axisX + axisY * axisY + axisZ * axisZ;
        if (lengthSquared == 0.0F || degrees == 0.0F) {
            return;
        }
        float invLength = Mth.invSqrt(lengthSquared);
        poseStack.mulPose(Axis.of(new Vector3f(axisX * invLength, axisY * invLength, axisZ * invLength))
                .rotationDegrees(degrees));
    }

    private boolean isAuraTargeting() {
        AuraFeature aura = FeatureManager.INSTANCE.getEnabled(AuraFeature.class);
        return aura != null && aura.getCurrentTarget() != null;
    }
}
