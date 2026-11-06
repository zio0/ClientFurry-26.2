package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.mining.MiningSessionSnapshot;

public final class AutoTpLootFeature extends PveFeature {
    public enum State {
        IDLE,
        SEEKING,
        RETURNING
    }

    public final NumberSetting range = register(new NumberSetting(
            "Range", 128.0D, 4.0D, 256.0D, 4.0D, " blocks"
    ));
    public final NumberSetting maxSpeed = register(new NumberSetting(
            "Max Speed", 35.0D, 0.5D, 35.0D, 0.5D, " blocks/tick"
    ));
    public final NumberSetting acceleration = register(new NumberSetting(
            "Acceleration", 0.5D, 0.05D, 1.0D, 0.05D, "x"
    ));
    public final NumberSetting returnRadius = register(new NumberSetting(
            "Return Radius", 0.5D, 0.1D, 2.0D, 0.1D, " blocks"
    ));
    public final NumberSetting targetTimeout = register(new NumberSetting(
            "Target Timeout", 15.0D, 2.0D, 60.0D, 1.0D, "s"
    ));

    private final MiningSessionSnapshot snapshot = new MiningSessionSnapshot();
    private ItemEntity target;
    private Vec3 origin;
    private State state = State.IDLE;
    private long stateTick;
    private long tick;
    private boolean controlledVelocity;

    public AutoTpLootFeature() {
        super(
                "AutoTpLoot",
                "Accelerates flight to the nearest grounded item and returns",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE,
                AutomationResource.MOVEMENT
        );
    }

    @Override
    protected void onPveEnable() {
        reset(false);
        this.snapshot.capture(Minecraft.getInstance().player);
    }

    @Override
    protected void onPveDisable() {
        cleanup();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        cleanup();
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!event.isPre()) {
            return;
        }
        LocalPlayer player = event.getPlayer();
        Minecraft client = Minecraft.getInstance();
        this.tick++;
        if (player == null || client.level == null || !player.isAlive()
                || !player.getAbilities().flying) {
            reset(true);
            return;
        }
        this.snapshot.capture(player);

        switch (this.state) {
            case IDLE -> acquireTarget(client, player);
            case SEEKING -> seek(player);
            case RETURNING -> returnToOrigin(player);
        }
    }

    public State getState() {
        return this.state;
    }

    public ItemEntity getTarget() {
        return this.target;
    }

    private void acquireTarget(Minecraft client, LocalPlayer player) {
        double rangeSquared = this.range.getValue() * this.range.getValue();
        double nearestDistance = rangeSquared;
        this.target = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)
                    || !item.isAlive()
                    || !item.onGround()) {
                continue;
            }
            double distance = item.distanceToSqr(player);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                this.target = item;
            }
        }
        if (this.target == null) {
            return;
        }
        this.origin = player.position();
        transition(State.SEEKING);
    }

    private void seek(LocalPlayer player) {
        long timeoutTicks = Math.max(1L, this.targetTimeout.getValue().longValue() * 20L);
        if (this.target == null || !this.target.isAlive()
                || this.tick - this.stateTick >= timeoutTicks) {
            transition(State.RETURNING);
            return;
        }
        accelerate(player, this.target.position());
    }

    private void returnToOrigin(LocalPlayer player) {
        if (this.origin == null) {
            reset(true);
            return;
        }
        double distance = player.position().distanceTo(this.origin);
        if (distance <= this.returnRadius.getValue()) {
            reset(true);
            return;
        }
        accelerate(player, this.origin);
    }

    private void accelerate(LocalPlayer player, Vec3 destination) {
        Vec3 delta = destination.subtract(player.position());
        double distance = delta.length();
        if (distance <= 1.0E-6D) {
            player.setDeltaMovement(Vec3.ZERO);
            this.controlledVelocity = true;
            return;
        }
        double speed = Math.min(
                this.maxSpeed.getValue(),
                distance * this.acceleration.getValue()
        );
        player.setDeltaMovement(delta.normalize().scale(speed));
        this.controlledVelocity = true;
    }

    private void transition(State next) {
        this.state = next;
        this.stateTick = this.tick;
        if (next == State.RETURNING) {
            this.target = null;
        }
    }

    private void reset(boolean stopMovement) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (stopMovement && this.controlledVelocity && player != null) {
            player.setDeltaMovement(Vec3.ZERO);
        }
        this.target = null;
        this.origin = null;
        this.state = State.IDLE;
        this.stateTick = this.tick;
        this.controlledVelocity = false;
    }

    private void cleanup() {
        Minecraft client = Minecraft.getInstance();
        reset(true);
        this.snapshot.restore(client);
    }
}
