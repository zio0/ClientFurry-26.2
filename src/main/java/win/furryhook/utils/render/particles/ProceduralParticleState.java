package win.furryhook.utils.render.particles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import win.furryhook.utils.render.particles.ProceduralParticleRenderer.Shape;

import java.util.Random;

public final class ProceduralParticleState {
    public enum Source {
        HIT,
        WORLD,
        WALK,
        ITEM
    }

    private static final double PI = 3.141593430080217D;
    private static final double Y_SLEEP_THRESHOLD = 0.010000003725784143D;
    private static final double OTHER_AXIS_DAMPING = 0.8999997820741558D;
    private static final double COLLISION_EPSILON_POSITIVE = 9.99999563509513E-5D;
    private static final double COLLISION_EPSILON_NEGATIVE = -9.999997135847346E-5D;
    private static final double VELOCITY_EPSILON = 1.0000000000249859E-5D;
    private static final float ROTATION_DAMPING = 0.9850000143051147F;
    private static final float COLLISION_SPIN = 0.30000001192092896F;
    private static final float FADE_IN_END = 0.20000000298023224F;
    private static final float FADE_OUT_START = 0.800000011920929F;
    private static final double SPAWN_VELOCITY = 0.2000000000678856D;

    /** Fraction of the lifetime spent scaling up from nothing on spawn. */
    private static final float GROW_END = 0.06F;
    /** Where the particle starts shrinking towards its death size. */
    private static final float SHRINK_START = 0.7F;
    /** How much of the size is given up by the end of the shrink. */
    private static final float SHRINK_DEPTH = 0.85F;
    /**
     * The collision box is deliberately smaller than the sprite: matching them
     * makes a wide particle bounce off surfaces it visually never touched, and at
     * the top of the size range it would rest a third of a block off the floor.
     */
    private static final float COLLISION_SCALE = 0.45F;

    private double previousX;
    private double previousY;
    private double previousZ;
    private double x;
    private double y;
    private double z;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private final int lifetime;
    private final Shape shape;
    private final Source source;
    private float age;
    private float rotation;
    private float rotationVelocity;
    private final float seed;
    private final float phase;
    /** Per-particle size offset in [-1, 1]; scaled by the configured variation. */
    private final float sizeJitter;

    public record Physics(
            double gravity,
            double bounciness,
            double drag,
            double floorFriction,
            double turbulence
    ) {
    }

    public ProceduralParticleState(double x,
                                   double y,
                                   double z,
                                   double velocityX,
                                   double velocityY,
                                   double velocityZ,
                                   int lifetime,
                                   Shape shape,
                                   Source source,
                                   Random random) {
        this.previousX = this.x = x;
        this.previousY = this.y = y;
        this.previousZ = this.z = z;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.lifetime = lifetime;
        this.shape = shape;
        this.source = source;
        this.rotation = (float) (random.nextDouble() * PI * 2.0D);
        this.rotationVelocity = (float) (random.nextDouble() - 0.5D)
                * 0.20000000298023224F;
        this.seed = random.nextFloat();
        this.phase = random.nextFloat() * 100.0F;
        this.sizeJitter = random.nextFloat() * 2.0F - 1.0F;
    }

    public static Vec3 randomVelocity(Random random) {
        return new Vec3(
                (random.nextDouble() - 0.5D) * SPAWN_VELOCITY,
                (random.nextDouble() - 0.5D) * SPAWN_VELOCITY,
                (random.nextDouble() - 0.5D) * SPAWN_VELOCITY
        );
    }

    public static boolean isPositionBlocked(Level level, Vec3 position) {
        BlockPos blockPos = BlockPos.containing(position);
        var state = level.getBlockState(blockPos);
        return !state.isAir() && !state.getCollisionShape(level, blockPos).isEmpty();
    }

    public boolean update(Level level,
                          float configuredSize,
                          float sizeVariation,
                          float delta,
                          Random random,
                          Physics physics) {
        this.age += delta;
        if (this.age >= this.lifetime) {
            return false;
        }

        float collisionSize = Math.max(
                0.005F,
                baseSize(configuredSize, sizeVariation) * COLLISION_SCALE
        );

        this.previousX = this.x;
        this.previousY = this.y;
        this.previousZ = this.z;
        this.rotation += this.rotationVelocity * delta;
        this.rotationVelocity *= ROTATION_DAMPING;

        this.velocityX += (random.nextDouble() - 0.5D) * physics.turbulence() * delta;
        this.velocityZ += (random.nextDouble() - 0.5D) * physics.turbulence() * delta;
        double drag = Math.pow(physics.drag(), delta);
        this.velocityX *= drag;
        this.velocityY = this.velocityY * drag - physics.gravity() * delta;
        this.velocityZ *= drag;
        if (Math.abs(this.velocityY) < Y_SLEEP_THRESHOLD) {
            this.velocityY = 0.0D;
        }

        double requestedX = this.velocityX * delta;
        double requestedY = this.velocityY * delta;
        double requestedZ = this.velocityZ * delta;
        AABB box = new AABB(
                this.x - collisionSize,
                this.y - collisionSize,
                this.z - collisionSize,
                this.x + collisionSize,
                this.y + collisionSize,
                this.z + collisionSize
        );

        double clippedY = clipAxis(level, box, requestedY, Direction.Axis.Y);
        if (clippedY != requestedY) {
            this.velocityY = -this.velocityY * physics.bounciness();
            this.rotationVelocity = (float) (random.nextDouble() - 0.5D)
                    * COLLISION_SPIN;
            if (requestedY < 0.0D) {
                this.velocityX *= physics.floorFriction();
                this.velocityZ *= physics.floorFriction();
            }
            clippedY = signedCollisionEpsilon(clippedY, requestedY);
        }
        this.y += clippedY;
        box = box.move(0.0D, clippedY, 0.0D);

        double clippedX = clipAxis(level, box, requestedX, Direction.Axis.X);
        if (clippedX != requestedX) {
            this.velocityX = -this.velocityX * physics.bounciness();
            this.velocityY *= OTHER_AXIS_DAMPING;
            this.velocityZ *= OTHER_AXIS_DAMPING;
            clippedX = signedCollisionEpsilon(clippedX, requestedX);
        }
        this.x += clippedX;
        box = box.move(clippedX, 0.0D, 0.0D);

        double clippedZ = clipAxis(level, box, requestedZ, Direction.Axis.Z);
        if (clippedZ != requestedZ) {
            this.velocityZ = -this.velocityZ * physics.bounciness();
            this.velocityY *= OTHER_AXIS_DAMPING;
            this.velocityX *= OTHER_AXIS_DAMPING;
            clippedZ = signedCollisionEpsilon(clippedZ, requestedZ);
        }
        this.z += clippedZ;

        if (Math.abs(this.velocityX) < VELOCITY_EPSILON) {
            this.velocityX = 0.0D;
        }
        if (Math.abs(this.velocityY) < VELOCITY_EPSILON) {
            this.velocityY = 0.0D;
        }
        if (Math.abs(this.velocityZ) < VELOCITY_EPSILON) {
            this.velocityZ = 0.0D;
        }
        return true;
    }

    public Vec3 interpolatedPosition(float tickDelta) {
        return new Vec3(
                this.previousX + (this.x - this.previousX) * tickDelta,
                this.previousY + (this.y - this.previousY) * tickDelta,
                this.previousZ + (this.z - this.previousZ) * tickDelta
        );
    }

    public float renderRotation(float tickDelta) {
        return this.rotation + this.rotationVelocity * tickDelta;
    }

    public float normalizedLife() {
        return Math.clamp(this.age / this.lifetime, 0.0F, 1.0F);
    }

    public float normalizedLife(float tickDelta) {
        return Math.clamp((this.age + tickDelta) / this.lifetime, 0.0F, 1.0F);
    }

    /**
     * Size before the lifetime envelope: the configured value spread by this
     * particle's own jitter, so a burst is a range of sizes rather than one size
     * repeated N times.
     */
    public float baseSize(float configuredSize, float sizeVariation) {
        float variation = Math.clamp(sizeVariation, 0.0F, 1.0F);
        return Math.max(0.001F, configuredSize * (1.0F + this.sizeJitter * variation));
    }

    /**
     * Rendered size: the base scaled by a pop-in on spawn and a shrink into
     * death. Fading only the alpha leaves a full-size ghost dissolving in place;
     * pulling the size down with it is what makes a spark look like it burns out.
     */
    public float renderSize(float configuredSize, float sizeVariation, float tickDelta) {
        float progress = normalizedLife(tickDelta);
        float growth = progress >= GROW_END
                ? 1.0F
                : smoothstep(progress / GROW_END);
        float shrink = progress <= SHRINK_START
                ? 1.0F
                : 1.0F - SHRINK_DEPTH * smoothstep(
                (progress - SHRINK_START) / (1.0F - SHRINK_START));
        return baseSize(configuredSize, sizeVariation) * growth * shrink;
    }

    private static float smoothstep(float value) {
        float clamped = Math.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    public float opacityEnvelope() {
        float progress = normalizedLife();
        if (progress < FADE_IN_END) {
            return progress / FADE_IN_END;
        }
        if (progress > FADE_OUT_START) {
            return (1.0F - progress) / FADE_IN_END;
        }
        return 1.0F;
    }

    public Shape shape() {
        return this.shape;
    }

    public Source source() {
        return this.source;
    }

    public float seed() {
        return this.seed;
    }

    public float phase() {
        return this.phase;
    }

    private static double clipAxis(Level level,
                                   AABB box,
                                   double movement,
                                   Direction.Axis axis) {
        if (movement == 0.0D) {
            return 0.0D;
        }
        AABB expanded = switch (axis) {
            case X -> box.expandTowards(movement, 0.0D, 0.0D);
            case Y -> box.expandTowards(0.0D, movement, 0.0D);
            case Z -> box.expandTowards(0.0D, 0.0D, movement);
        };
        return Shapes.collide(
                axis,
                box,
                level.getBlockCollisions(null, expanded),
                movement
        );
    }

    private static double signedCollisionEpsilon(double clipped, double requested) {
        if (Math.abs(clipped) >= COLLISION_EPSILON_POSITIVE) {
            return clipped;
        }
        return requested < 0.0D
                ? COLLISION_EPSILON_POSITIVE
                : COLLISION_EPSILON_NEGATIVE;
    }
}
