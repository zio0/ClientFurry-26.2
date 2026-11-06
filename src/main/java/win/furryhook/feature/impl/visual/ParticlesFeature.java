package win.furryhook.feature.impl.visual;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.particles.ProceduralParticleRenderer;
import win.furryhook.utils.render.particles.ProceduralParticleRenderer.Shape;
import win.furryhook.utils.render.particles.ProceduralParticleRenderer.Sprite;
import win.furryhook.utils.render.particles.ProceduralParticleState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ParticlesFeature extends Feature implements MinecraftContext {
    private static final double SPAWN_PI = 3.1415928936382223D;
    private static final int WORLD_BATCH_SIZE = 10;
    private static final double WALK_SPEED_THRESHOLD = 0.004D;
    private static final int MAX_TRACKED_ITEMS = 32;
    private static final double PHYSICS_SCALE = 0.01D;

    /**
     * Burst sizes are fixed rather than exposed: the population is already
     * bounded by lifetime times emit rate, and every mode runs uncapped, so a
     * per-burst knob only bought a way to make the same cloud arrive lumpier.
     */
    private static final int HIT_BURST = 15;
    private static final int WALK_BURST = 1;
    private static final int JUMP_BURST = 6;
    private static final int LAND_BURST = 10;

    private static final String MODE_HIT = "Hit";
    private static final String MODE_WORLD = "World";
    private static final String MODE_WALKING = "Walking";
    private static final String MODE_FALLING_ITEMS = "Falling Items";

    private static final String ITEM_ENDER_PEARL = "Ender Pearl";
    private static final String ITEM_TRIDENT = "Trident";
    private static final String ITEM_SNOWBALL = "Snowball";
    private static final String ITEM_EGG = "Egg";
    private static final String ITEM_POTIONS = "Potions";
    private static final String ITEM_ARROWS = "Arrows";
    private static final String ITEM_FISHING_BOBBER = "Fishing Bobber";
    private static final String ITEM_DROPPED = "Dropped Items";

    public final MultiSelectSetting workFrom = register(new MultiSelectSetting(
            "Work From",
            Set.of(MODE_HIT),
            MODE_HIT, MODE_WORLD, MODE_WALKING, MODE_FALLING_ITEMS
    ).configKey("render.particles.workfrom"));

    public final ModeSetting shape = register(new ModeSetting(
            "Shape",
            Shape.RANDOM.displayName(),
            Shape.displayNames()
    ).configKey("render.particles.texture"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.particles.colormode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color", 0xFFFFFFFF
    ).configKey("render.particles.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced",
            false
    ).asExpander().configKey("render.particles.advanced"));

    public final NumberSetting lifetime = register(new NumberSetting(
            "Lifetime", 60.0D, 10.0D, 200.0D, 1.0D, " ticks"
    ).configKey("render.particles.lifetime").advancedOnly());
    public final NumberSetting size = register(new NumberSetting(
            "Size", 0.15D, 0.02D, 1.0D, 0.01D, " blocks"
    ).configKey("render.particles.size").advancedOnly());
    public final NumberSetting sizeVariation = register(new NumberSetting(
            "Size Variation", 0.35D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.particles.sizevariation").advancedOnly());
    public final NumberSetting glow = register(new NumberSetting(
            "Glow", 0.5D, 0.0D, 1.5D, 0.05D, ""
    ).configKey("render.particles.glow").advancedOnly());

    public final BooleanSetting bloom = register(new BooleanSetting(
            "Bloom", true
    ).configKey("render.particles.bloom").advancedOnly());
    public final NumberSetting bloomIntensity = register(new NumberSetting(
            "Bloom Intensity", 1.0D, 0.0D, 3.0D, 0.05D, ""
    ).configKey("render.particles.bloomintensity")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting bloomRadius = register(new NumberSetting(
            "Bloom Radius", 3.0D, 1.0D, 4.0D, 1.0D, ""
    ).configKey("render.particles.bloomradius")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting hdrBoost = register(new NumberSetting(
            "HDR Boost", 2.0D, 0.5D, 6.0D, 0.1D, ""
    ).configKey("render.particles.hdrboost")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting anamorphic = register(new NumberSetting(
            "Anamorphic", 0.35D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.particles.anamorphic")
            .visibleWhen(this.bloom::getValue).advancedOnly());
    public final NumberSetting chromatic = register(new NumberSetting(
            "Chromatic", 0.5D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.particles.chromatic").advancedOnly());
    public final NumberSetting birthFlash = register(new NumberSetting(
            "Birth Flash", 1.0D, 0.0D, 2.0D, 0.05D, ""
    ).configKey("render.particles.birthflash").advancedOnly());

    public final NumberSetting gravity = register(new NumberSetting(
            "Gravity", -0.3D, -2.0D, 2.0D, 0.1D, ""
    ).configKey("render.particles.gravity").advancedOnly());
    public final NumberSetting bounciness = register(new NumberSetting(
            "Bounciness", 0.55D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.particles.bounciness").advancedOnly());
    public final NumberSetting drag = register(new NumberSetting(
            "Drag", 0.96D, 0.85D, 1.0D, 0.01D, ""
    ).configKey("render.particles.drag").advancedOnly());
    public final NumberSetting floorFriction = register(new NumberSetting(
            "Floor Friction", 0.75D, 0.0D, 1.0D, 0.05D, ""
    ).configKey("render.particles.floorfriction").advancedOnly());
    public final NumberSetting turbulence = register(new NumberSetting(
            "Turbulence", 0.2D, 0.0D, 2.0D, 0.1D, ""
    ).configKey("render.particles.turbulence").advancedOnly());

    public final NumberSetting spawnRate = register(new NumberSetting(
            "Spawn Rate", 1.0D, 1.0D, 5.0D, 1.0D, " ticks"
    ).configKey("render.particles.spawnrate")
            .visibleWhen(() -> this.workFrom.isSelected(MODE_WORLD)).advancedOnly());
    public final NumberSetting worldRadius = register(new NumberSetting(
            "Spawn Radius", 40.0D, 10.0D, 40.0D, 1.0D, " blocks"
    ).configKey("render.particles.radius")
            .visibleWhen(() -> this.workFrom.isSelected(MODE_WORLD)).advancedOnly());

    public final NumberSetting walkRate = register(new NumberSetting(
            "Emit Rate", 8.0D, 1.0D, 30.0D, 1.0D, "/s"
    ).configKey("render.particles.walkrate")
            .visibleWhen(() -> this.workFrom.isSelected(MODE_WALKING)).advancedOnly());

    public final MultiSelectSetting itemTypes = register(new MultiSelectSetting(
            "Item Types",
            Set.of(ITEM_ENDER_PEARL, ITEM_TRIDENT),
            ITEM_ENDER_PEARL, ITEM_TRIDENT, ITEM_SNOWBALL, ITEM_EGG,
            ITEM_POTIONS, ITEM_ARROWS, ITEM_FISHING_BOBBER, ITEM_DROPPED
    ).configKey("render.particles.itemtypes")
            .visibleWhen(() -> this.workFrom.isSelected(MODE_FALLING_ITEMS)));
    public final NumberSetting itemRate = register(new NumberSetting(
            "Emit Rate", 12.0D, 1.0D, 30.0D, 1.0D, "/s"
    ).configKey("render.particles.itemrate")
            .visibleWhen(() -> this.workFrom.isSelected(MODE_FALLING_ITEMS)).advancedOnly());

    private final List<ProceduralParticleState> particles = new ArrayList<>();
    private final List<Sprite> sprites = new ArrayList<>();
    private final ProceduralParticleRenderer renderer = new ProceduralParticleRenderer();
    private final Random random = new Random();
    private final Map<Entity, Double> itemBudgets = new HashMap<>();

    private double walkBudget;
    private boolean wasOnGround = true;

    public ParticlesFeature() {
        super(
                "Particles", "Spawns procedural particles from hits, the world, movement or thrown items",
                FeatureCategory.VISUAL,
                BindSetting.UNBOUND
        );
    }

    public static ParticlesFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(ParticlesFeature.class);
    }

    public void onAttack(Entity target) {
        if (this.workFrom.isSelected(MODE_HIT) && target instanceof LivingEntity living) {
            spawnHit(living);
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }

        if (this.workFrom.isSelected(MODE_WORLD)) {
            spawnWorldBatch();
        }
        if (this.workFrom.isSelected(MODE_WALKING)) {
            tickWalking();
        }
        if (this.workFrom.isSelected(MODE_FALLING_ITEMS)) {
            tickFallingItems();
        }

        if (this.particles.isEmpty()) {
            return;
        }

        float configuredSize = this.size.getValue().floatValue();
        float variation = this.sizeVariation.getValue().floatValue();
        ProceduralParticleState.Physics physics = currentPhysics();
        Iterator<ProceduralParticleState> iterator = this.particles.iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().update(mc.level, configuredSize, variation, 1.0F, this.random, physics)) {
                iterator.remove();
            }
        }
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
        this.renderer.release();
    }

    private void clear() {
        this.particles.clear();
        this.sprites.clear();
        this.walkBudget = 0.0D;
        this.wasOnGround = true;
        this.itemBudgets.clear();
    }

    public void renderWorld(float tickDelta) {
        if (mc.player == null || mc.level == null || this.particles.isEmpty()) {
            return;
        }

        int baseColor = ColorMode.resolve(this.colorMode, this.color);
        float configuredSize = this.size.getValue().floatValue();
        float variation = this.sizeVariation.getValue().floatValue();
        this.sprites.clear();
        for (ProceduralParticleState particle : this.particles) {
            this.sprites.add(new Sprite(
                    particle.interpolatedPosition(tickDelta),
                    particle.renderSize(configuredSize, variation, tickDelta),
                    ColorUtil.multiplyAlpha(baseColor, particle.opacityEnvelope()),
                    particle.shape(),
                    particle.renderRotation(tickDelta),
                    particle.normalizedLife(),
                    particle.seed(),
                    particle.phase()
            ));
        }
        this.renderer.render(
                this.sprites,
                this.glow.getValue().floatValue(),
                false,
                currentBloom()
        );
    }

    private ProceduralParticleRenderer.Bloom currentBloom() {
        float flash = this.birthFlash.getValue().floatValue();
        float chroma = this.chromatic.getValue().floatValue();
        if (!this.bloom.getValue()) {
            return new ProceduralParticleRenderer.Bloom(
                    false, 0.0F, 1, 1.0F, 0.0F, flash, chroma
            );
        }
        return new ProceduralParticleRenderer.Bloom(
                true,
                this.bloomIntensity.getValue().floatValue(),
                this.bloomRadius.getValue().intValue(),
                this.hdrBoost.getValue().floatValue(),
                this.anamorphic.getValue().floatValue(),
                flash,
                chroma
        );
    }

    private ProceduralParticleState.Physics currentPhysics() {
        return new ProceduralParticleState.Physics(
                this.gravity.getValue() * PHYSICS_SCALE,
                this.bounciness.getValue(),
                this.drag.getValue(),
                this.floorFriction.getValue(),
                this.turbulence.getValue() * PHYSICS_SCALE
        );
    }

    private void spawnHit(LivingEntity entity) {
        Vec3 base = entity.position();
        int configuredLifetime = this.lifetime.getValue().intValue();
        for (int index = 0; index < HIT_BURST; index++) {
            Vec3 position = base.add(
                    (this.random.nextDouble() - 0.5D) * entity.getBbWidth(),
                    this.random.nextDouble() * entity.getBbHeight(),
                    (this.random.nextDouble() - 0.5D) * entity.getBbWidth()
            );
            spawnAt(position, ProceduralParticleState.randomVelocity(this.random), configuredLifetime, ProceduralParticleState.Source.HIT);
        }
    }

    private void spawnWorldBatch() {
        // Uncapped on purpose: the population settles at batch size times
        // lifetime on its own, and a cap only ever showed up as the cloud
        // visibly refusing to replenish.
        Vec3 center = mc.player.position();
        double configuredRadius = this.worldRadius.getValue();
        int configuredLifetime = this.lifetime.getValue().intValue();
        for (int index = 0; index < WORLD_BATCH_SIZE; index++) {
            double angle = this.random.nextDouble() * SPAWN_PI * 2.0D;
            double distance = this.random.nextDouble() * configuredRadius;
            Vec3 position = center.add(
                    Math.cos(angle) * distance,
                    (this.random.nextDouble() - 0.5D) * distance,
                    Math.sin(angle) * distance
            );
            if (ProceduralParticleState.isPositionBlocked(mc.level, position)) {
                continue;
            }
            spawnAt(position, ProceduralParticleState.randomVelocity(this.random), configuredLifetime, ProceduralParticleState.Source.WORLD);
        }
    }

    private void tickWalking() {
        boolean onGround = mc.player.onGround();
        Vec3 movement = mc.player.getDeltaMovement();
        boolean leftGround = this.wasOnGround && !onGround;
        boolean landed = !this.wasOnGround && onGround;
        this.wasOnGround = onGround;

        // Kick-off and touch-down are the two moments the feet actually do
        // something, so they get their own burst instead of relying on whatever
        // the steady emitter happens to hand out that tick.
        if (leftGround && movement.y > 0.0D) {
            spawnFootParticles(JUMP_BURST, 1.4D, 0.6D);
        }
        if (landed) {
            spawnFootParticles(LAND_BURST, 1.6D, 0.15D);
        }

        // No ground check: mid-air is exactly where the trail has to keep going,
        // otherwise every jump punches a gap into it.
        boolean moving = movement.horizontalDistance() > WALK_SPEED_THRESHOLD
                || Math.abs(movement.y) > WALK_SPEED_THRESHOLD;
        if (!moving) {
            this.walkBudget = 0.0D;
            return;
        }

        this.walkBudget += this.walkRate.getValue() / 20.0D;
        int bursts = (int) this.walkBudget;
        this.walkBudget -= bursts;
        spawnFootParticles(bursts * WALK_BURST, 1.0D, 0.5D);
    }

    private void spawnFootParticles(int count, double spread, double velocityScale) {
        if (count <= 0) {
            return;
        }

        Vec3 feet = mc.player.position();
        int configuredLifetime = this.lifetime.getValue().intValue();
        double width = mc.player.getBbWidth() * 0.6D * spread;
        for (int index = 0; index < count; index++) {
            Vec3 position = feet.add(
                    (this.random.nextDouble() - 0.5D) * width,
                    0.05D,
                    (this.random.nextDouble() - 0.5D) * width
            );
            if (ProceduralParticleState.isPositionBlocked(mc.level, position)) {
                continue;
            }
            Vec3 velocity = ProceduralParticleState.randomVelocity(this.random).scale(velocityScale);
            spawnAt(position, velocity, configuredLifetime, ProceduralParticleState.Source.WALK);
        }
    }

    private void tickFallingItems() {
        Set<Entity> tracked = collectTrackedItems();
        this.itemBudgets.keySet().retainAll(tracked);
        if (tracked.isEmpty()) {
            return;
        }

        int configuredLifetime = this.lifetime.getValue().intValue();
        double perTick = this.itemRate.getValue() / 20.0D;
        for (Entity entity : tracked) {
            if (entity.getDeltaMovement().lengthSqr() < 1.0E-5D) {
                continue;
            }

            boolean firstSeen = !this.itemBudgets.containsKey(entity);
            double budget = this.itemBudgets.getOrDefault(entity, 0.0D) + perTick + (firstSeen ? 1.0D : 0.0D);
            int toSpawn = (int) budget;
            this.itemBudgets.put(entity, budget - toSpawn);
            for (int index = 0; index < toSpawn; index++) {
                Vec3 position = entity.position().add(
                        (this.random.nextDouble() - 0.5D) * 0.1D,
                        (this.random.nextDouble() - 0.5D) * 0.1D,
                        (this.random.nextDouble() - 0.5D) * 0.1D
                );
                spawnAt(position, ProceduralParticleState.randomVelocity(this.random), configuredLifetime, ProceduralParticleState.Source.ITEM);
            }
        }
    }

    private Set<Entity> collectTrackedItems() {
        Set<Entity> tracked = new LinkedHashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (tracked.size() >= MAX_TRACKED_ITEMS) {
                break;
            }
            if (matchesTrackedItem(entity)) {
                tracked.add(entity);
            }
        }
        return tracked;
    }

    private boolean matchesTrackedItem(Entity entity) {
        if (entity instanceof ThrownTrident) {
            return this.itemTypes.isSelected(ITEM_TRIDENT);
        }
        if (entity instanceof ThrownEnderpearl) {
            return this.itemTypes.isSelected(ITEM_ENDER_PEARL);
        }
        if (entity instanceof Snowball) {
            return this.itemTypes.isSelected(ITEM_SNOWBALL);
        }
        if (entity instanceof ThrownEgg) {
            return this.itemTypes.isSelected(ITEM_EGG);
        }
        if (entity instanceof AbstractThrownPotion) {
            return this.itemTypes.isSelected(ITEM_POTIONS);
        }
        if (entity instanceof AbstractArrow) {
            return this.itemTypes.isSelected(ITEM_ARROWS);
        }
        if (entity instanceof FishingHook) {
            return this.itemTypes.isSelected(ITEM_FISHING_BOBBER);
        }
        if (entity instanceof ItemEntity) {
            return this.itemTypes.isSelected(ITEM_DROPPED);
        }
        return false;
    }

    private void spawnAt(Vec3 position, Vec3 velocity, int lifetime, ProceduralParticleState.Source source) {
        this.particles.add(new ProceduralParticleState(
                position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z,
                lifetime,
                selectedShape(),
                source,
                this.random
        ));
    }

    private Shape selectedShape() {
        return Shape.fromDisplayName(this.shape.getValue()).resolve(this.random);
    }
}
