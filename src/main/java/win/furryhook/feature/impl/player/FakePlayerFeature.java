package win.furryhook.feature.impl.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.mixin.accessor.EntityAccessor;

import java.util.UUID;

public final class FakePlayerFeature extends Feature {

    private static final int FAKE_PLAYER_ENTITY_ID = -23_467;
    private static final UUID FAKE_PLAYER_UUID = UUID.fromString("66123666-6666-6666-6666-666666666600");
    private static final String FAKE_PLAYER_NAME = "FakePlayer";

    /** Gliding / fall-flying shared flag bit. */
    private static final int SHARED_FLAG_GLIDING = 7;

    private final ModeSetting mode = register(new ModeSetting(
            "Mode", "AFK", "AFK", "Elytra"
    ));

    private final NumberSetting elytraSpeed = register(new NumberSetting(
            "Elytra Speed", 1.0D, 0.2D, 2.5D, 0.1D, " b/t"
    ).visibleWhen(() -> mode.is("Elytra")));

    private RemotePlayer fakePlayer;
    private float moveForward;
    private float moveStrafe;
    private String lastMode;
    private float flightYaw = Float.NaN;
    private Vec3 elytraVelocity = Vec3.ZERO;
    private Vec3 lastFlightPos;

    public FakePlayerFeature() {
        super("FakePlayer", "AFK dummy, or an elytra target that glides away to test overtake", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    public static FakePlayerFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(FakePlayerFeature.class);
    }

    public void ensureSpawned() {
        spawnFakePlayerIfNeeded(Minecraft.getInstance());
    }

    public void despawn() {
        removeFakePlayer();
    }

    public RemotePlayer getFakePlayerEntity() {
        return fakePlayer;
    }

    public boolean hasFakePlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null
                && fakePlayer != null
                && !fakePlayer.isRemoved()
                && mc.level.getEntity(fakePlayer.getId()) == fakePlayer;
    }

    public void moveFakeTo(Vec3 position, float yaw, float pitch) {
        if (position == null || fakePlayer == null) {
            return;
        }
        fakePlayer.snapTo(position.x, position.y, position.z, yaw, pitch);
        fakePlayer.setYHeadRot(yaw);
        fakePlayer.yBodyRot = yaw;
    }

    public int getFakePlayerEntityId() {
        return FAKE_PLAYER_ENTITY_ID;
    }

    public boolean isFakePlayer(Entity entity) {
        return fakePlayer != null && (entity == fakePlayer || entity.getId() == fakePlayer.getId());
    }

    public void registerHit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || fakePlayer == null) {
            return;
        }
        if (fakePlayer.hurtTime != 0) {
            return;
        }

        mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 1.0f);

        if (mc.player.fallDistance > 0.0f) {
            mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
        } else {
            mc.level.playSound(mc.player, fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.0f);
        }

        fakePlayer.handleDamageEvent(mc.level.damageSources().generic());
        fakePlayer.setHealth(fakePlayer.getHealth() + fakePlayer.getAbsorptionAmount() - 1.0f);
        if (fakePlayer.isDeadOrDying()) {
            fakePlayer.setHealth(10.0f);
            if (mc.player.connection != null) {
                new ClientboundEntityEventPacket(fakePlayer, (byte) 35).handle(mc.player.connection);
            }
        }
    }

    @Override
    protected void onEnable() {
        spawnFakePlayerIfNeeded(Minecraft.getInstance());
    }

    @Override
    protected void onDisable() {
        removeFakePlayer();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        if (mc.level == null || mc.player == null) {
            removeFakePlayer();
            return;
        }

        // Switching AFK <-> Elytra respawns a clean dummy so leftover state (elytra, gliding
        // flag, no-gravity, heading) never carries over between modes.
        if (lastMode != null && !lastMode.equals(mode.getValue())) {
            removeFakePlayer();
        }

        boolean existsInCurrentWorld = fakePlayer != null && mc.level.getEntity(fakePlayer.getId()) == fakePlayer;
        if (fakePlayer == null || fakePlayer.isRemoved() || !existsInCurrentWorld) {
            spawnFakePlayerIfNeeded(mc);
        }
        lastMode = mode.getValue();

        if (mode.is("Elytra")) {
            updateElytraFlight(mc);
        } else {
            updateFakeMovement(mc);
        }
    }

    @EventTarget
    public void onKey(KeyboardInputEvent event) {
        if (fakePlayer == null || Minecraft.getInstance().gui.screen() != null) {
            return;
        }

        int key = event.getKey();
        int action = event.getAction();

        if (key == 265) {
            moveForward = isKeyDown(action) ? 1.0f : 0.0f;
        } else if (key == 264) {
            moveForward = isKeyDown(action) ? -1.0f : 0.0f;
        } else if (key == 263) {
            moveStrafe = isKeyDown(action) ? 1.0f : 0.0f;
        } else if (key == 262) {
            moveStrafe = isKeyDown(action) ? -1.0f : 0.0f;
        }
    }

    private void spawnFakePlayerIfNeeded(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }

        if (fakePlayer != null && !fakePlayer.isRemoved() && mc.level.getEntity(fakePlayer.getId()) == fakePlayer) {
            return;
        }

        removeFakePlayer();

        Entity existing = mc.level.getEntity(FAKE_PLAYER_ENTITY_ID);
        if (existing != null) {
            existing.discard();
        }

        fakePlayer = new RemotePlayer(mc.level, new GameProfile(FAKE_PLAYER_UUID, FAKE_PLAYER_NAME));
        fakePlayer.setId(FAKE_PLAYER_ENTITY_ID);
        fakePlayer.copyPosition(mc.player);
        fakePlayer.setYHeadRot(mc.player.getYHeadRot());
        fakePlayer.yBodyRot = mc.player.yBodyRot;
        fakePlayer.setPose(mc.player.getPose());

        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().copy());
        fakePlayer.setItemInHand(InteractionHand.OFF_HAND, mc.player.getOffhandItem().copy());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                fakePlayer.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
            }
        }

        fakePlayer.setHealth(Math.max(1.0f, mc.player.getHealth()));
        fakePlayer.setAbsorptionAmount(mc.player.getAbsorptionAmount());
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 9999, 2, false, false));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 9999, 4, false, false));
        fakePlayer.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 9999, 1, false, false));

        mc.level.addEntity(fakePlayer);
    }

    private void removeFakePlayer() {
        moveForward = 0.0f;
        moveStrafe = 0.0f;
        flightYaw = Float.NaN;
        elytraVelocity = Vec3.ZERO;
        lastFlightPos = null;
        if (fakePlayer != null) {
            fakePlayer.discard();
            fakePlayer = null;
        }
    }

    /**
     * Elytra test target setup, run at tick HEAD: equips an elytra, forces the gliding flag, and
     * publishes the glide velocity on a fixed heading (captured from your view when flight starts) at
     * {@link #elytraSpeed} blocks/tick, flying level. The {@code deltaMovement} is the real glide
     * velocity so ElytraTarget's lead point and its 1.25x overtake lock both read a moving, gliding
     * {@link RemotePlayer}. The actual position step is deferred to {@link #advanceElytraPostTick()}
     * so it lands after the entity's own tick — otherwise render interpolation collapses and the
     * dummy stutters (which is what threw the chase backwards).
     */
    private void updateElytraFlight(Minecraft mc) {
        if (fakePlayer == null || mc.player == null) {
            return;
        }

        if (!fakePlayer.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            fakePlayer.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        }
        ((EntityAccessor) fakePlayer).invokeSetSharedFlag(SHARED_FLAG_GLIDING, true);
        fakePlayer.setNoGravity(true);

        if (Float.isNaN(flightYaw)) {
            flightYaw = mc.player.getYRot();
        }
        if (lastFlightPos == null) {
            lastFlightPos = fakePlayer.position();
        }

        double speed = elytraSpeed.getValue();
        double yawRad = Math.toRadians(flightYaw);
        elytraVelocity = new Vec3(-Math.sin(yawRad) * speed, 0.0D, Math.cos(yawRad) * speed);

        fakePlayer.setDeltaMovement(elytraVelocity);
        fakePlayer.setYRot(flightYaw);
        fakePlayer.setYHeadRot(flightYaw);
        fakePlayer.yBodyRot = flightYaw;
        fakePlayer.setXRot(0.0f);
        fakePlayer.walkAnimation.setSpeed(0.0f);
    }

    /**
     * Steps the elytra dummy forward once, at tick TAIL (after the level has ticked entities, which
     * resets {@code xOld} to the current position). By setting {@code xOld} to the previous position
     * and only then advancing, the renderer interpolates old -> new across the frame and the flight
     * is smooth instead of teleporting one step per tick.
     */
    public void advanceElytraPostTick() {
        if (!isEnabled() || !mode.is("Elytra") || fakePlayer == null || lastFlightPos == null) {
            return;
        }

        Vec3 base = lastFlightPos;
        Vec3 next = base.add(elytraVelocity);

        fakePlayer.xOld = base.x;
        fakePlayer.yOld = base.y;
        fakePlayer.zOld = base.z;
        fakePlayer.setPos(next.x, next.y, next.z);
        lastFlightPos = next;
    }

    private void updateFakeMovement(Minecraft mc) {
        if (fakePlayer == null || mc.player == null) {
            return;
        }

        if (moveForward == 0.0f && moveStrafe == 0.0f) {
            fakePlayer.setSprinting(false);
            fakePlayer.setDeltaMovement(0.0, fakePlayer.getDeltaMovement().y, 0.0);
            fakePlayer.walkAnimation.setSpeed(0.0f);
            return;
        }

        float yaw = mc.player.getYRot();
        double speed = 0.2;
        double motionX = moveStrafe * Math.cos(Math.toRadians(yaw)) - moveForward * Math.sin(Math.toRadians(yaw));
        double motionZ = moveForward * Math.cos(Math.toRadians(yaw)) + moveStrafe * Math.sin(Math.toRadians(yaw));
        Vec3 velocity = new Vec3(motionX * speed, fakePlayer.getDeltaMovement().y, motionZ * speed);
        fakePlayer.setDeltaMovement(velocity);
        fakePlayer.move(MoverType.SELF, velocity);
        fakePlayer.setSprinting(true);
    }

    private boolean isKeyDown(int action) {
        return action == 1 || action == 2;
    }
}
