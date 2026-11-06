package win.furryhook.utils.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;

public final class AttackTiming {
    private static final int[] PATTERN = {10, 10, 10, 13};
    private static final float READY_CHARGE = 0.9F;

    private int extraDelayTicks;
    private int hitCounter;

    public void tick() {
        if (this.extraDelayTicks > 0) {
            this.extraDelayTicks--;
        }
    }

    public void onSwingPacket(boolean usePattern, boolean tpsSync) {
        float pattern = usePattern ? PATTERN[this.hitCounter % PATTERN.length] : 0.0F;
        float scale = 1.0F;
        if (tpsSync) {
            float tps = Mth.clamp(ServerTickSync.INSTANCE.effectiveTps(), 1.0F, 20.0F);
            scale = 20.0F / tps;
        }
        this.extraDelayTicks = Math.max(0, Math.round(pattern * scale));
    }

    public boolean cooldownReady(LocalPlayer player, int ticksAhead) {
        int delayLeft = this.extraDelayTicks - ticksAhead;
        if (player.getMainHandItem().is(Items.MACE)) {
            return delayLeft <= 0;
        }
        return player.getAttackStrengthScale(ticksAhead + 0.5F) > READY_CHARGE && delayLeft <= 0;
    }

    public void onAttack() {
        this.hitCounter++;
    }

    public int hitCounter() {
        return this.hitCounter;
    }

    public void reset() {
        this.extraDelayTicks = 0;
    }
}
