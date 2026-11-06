package win.furryhook.mixin.accessor;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code head}/{@code body}/{@code rightArm}/... are declared on {@link HumanoidModel}, not on
 * {@link net.minecraft.client.model.player.PlayerModel} itself - {@code @Shadow} only resolves
 * fields declared directly on the mixin's target class, so a mixin on {@code PlayerModel} can't
 * shadow them. An accessor on the actual declaring class works instead: Mixin implements this
 * interface onto {@code HumanoidModel}'s bytecode, and every subclass (including
 * {@code PlayerModel}) inherits it, so casting a {@code PlayerModel} instance to this interface
 * is a normal, valid upcast.
 */
@Mixin(HumanoidModel.class)
public interface HumanoidModelAccessor {
    @Accessor("head")
    ModelPart furryhook$getHead();

    @Accessor("body")
    ModelPart furryhook$getBody();

    @Accessor("rightArm")
    ModelPart furryhook$getRightArm();

    @Accessor("leftArm")
    ModelPart furryhook$getLeftArm();

    @Accessor("rightLeg")
    ModelPart furryhook$getRightLeg();

    @Accessor("leftLeg")
    ModelPart furryhook$getLeftLeg();
}
