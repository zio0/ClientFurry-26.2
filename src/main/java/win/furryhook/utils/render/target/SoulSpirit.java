package win.furryhook.utils.render.target;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One soul of the "Души" Target ESP mode.
 *
 * <p>A soul is not a particle: it has a body, a tail, inertia and a character. It chases an
 * anchor point orbiting the target with a second order critically damped spring, which is what
 * makes the motion frame-rate independent and free of the micro-stutter a plain
 * {@code lerp(pos, target, 0.2f)} produces. Position history is written at a fixed timestep, so
 * the trail keeps its shape through frame spikes.
 */
final class SoulSpirit {
    /** Position samples kept for the tail; at 1/120 s per sample that is ~0.8 s of history. */
    static final int HISTORY = 96;

    private static final float REST_FREQUENCY = 1.6F;
    private static final float REST_DAMPING = 1.0F;
    private static final float LAUNCH_FREQUENCY = 3.2F;
    private static final float LAUNCH_DAMPING = 0.45F;

    final int index;

    double x;
    double y;
    double z;
    double vx;
    double vy;
    double vz;

    /** Per-soul randomisation so the group never reads as a rotating ring. */
    final float phase;
    final float orbitFrequency;
    final float bobFrequency;
    final float orbitRadius;
    final float orbitHeight;
    final float pulsePhase;
    final float jitterSeed;

    /** Seconds left of the boosted, under-damped chase used when the target changes. */
    float launch;
    /** Seconds left before this soul starts following a new target (staggered departure). */
    float delay;
    /** Impact flash, 1 right after a landed hit, decaying to 0. */
    float flash;

    private final float[] history = new float[HISTORY * 3];
    private int historyCount;
    private int historyHead;

    SoulSpirit(int index, int count) {
        this.index = index;
        float golden = 2.399963F;
        this.phase = index * golden;
        this.orbitFrequency = 0.55F + hash(index, 0) * 0.35F;
        this.bobFrequency = 0.8F + hash(index, 1) * 0.7F;
        this.orbitRadius = 0.75F + hash(index, 2) * 0.45F;
        this.orbitHeight = 0.28F + hash(index, 3) * 0.4F;
        this.pulsePhase = hash(index, 4) * (float) (Math.PI * 2.0D);
        this.jitterSeed = hash(index, 5) * 12.9F;
        this.delay = index * 0.06F;
        if (count <= 1) {
            this.delay = 0.0F;
        }
    }

    /** Drops the soul at {@code position} with no velocity and an empty tail. */
    void placeAt(Vec3 position) {
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.vx = 0.0D;
        this.vy = 0.0D;
        this.vz = 0.0D;
        this.historyCount = 0;
        this.historyHead = 0;
    }

    /**
     * Kicks the soul off its orbit toward a new anchor: the spring goes under-damped so it
     * overshoots and swings back, and a sideways impulse turns the straight line into an arc.
     */
    void launchTo(Vec3 anchor, Vec3 lateral) {
        this.launch = 0.42F;
        this.delay = this.index * 0.06F;
        double dx = anchor.x - this.x;
        double dy = anchor.y - this.y;
        double dz = anchor.z - this.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        float sign = (this.index & 1) == 0 ? 1.0F : -1.0F;
        double push = Math.min(6.0D, length) * 0.55D * sign;
        this.vx += lateral.x * push;
        this.vy += lateral.y * push + 0.35D;
        this.vz += lateral.z * push;
    }

    void impact() {
        this.flash = 1.0F;
    }

    /**
     * Advances one fixed timestep and appends a history sample. {@code dt} must be constant —
     * the caller drives this from an accumulator, never straight from the frame time.
     */
    void step(float dt, Vec3 anchor, float clock, float speedScale) {
        if (this.delay > 0.0F) {
            this.delay -= dt;
        }

        boolean launching = this.launch > 0.0F && this.delay <= 0.0F;
        float frequency = (launching ? LAUNCH_FREQUENCY : REST_FREQUENCY) * speedScale;
        float damping = launching ? LAUNCH_DAMPING : REST_DAMPING;
        if (this.launch > 0.0F && this.delay <= 0.0F) {
            this.launch = Math.max(0.0F, this.launch - dt);
        }

        double targetX = anchor.x;
        double targetY = anchor.y;
        double targetZ = anchor.z;
        if (this.delay > 0.0F) {
            // Still hesitating: hold the old spot instead of snapping to the new target.
            targetX = this.x;
            targetY = this.y;
            targetZ = this.z;
        }

        float omega = (float) (Math.PI * 2.0D) * frequency;
        double ax = (targetX - this.x) * omega * omega - this.vx * 2.0D * damping * omega;
        double ay = (targetY - this.y) * omega * omega - this.vy * 2.0D * damping * omega;
        double az = (targetZ - this.z) * omega * omega - this.vz * 2.0D * damping * omega;

        this.vx += ax * dt;
        this.vy += ay * dt;
        this.vz += az * dt;

        // Low frequency wander so a soul is never perfectly still, without adding grain.
        float wander = 0.9F * dt;
        this.vx += Math.sin(clock * 0.83F + this.jitterSeed) * wander;
        this.vy += Math.sin(clock * 1.11F + this.jitterSeed * 1.7F) * wander * 0.6F;
        this.vz += Math.cos(clock * 0.97F + this.jitterSeed * 2.3F) * wander;

        this.x += this.vx * dt;
        this.y += this.vy * dt;
        this.z += this.vz * dt;

        if (this.flash > 0.0F) {
            this.flash = Math.max(0.0F, this.flash - dt * 6.5F);
        }

        pushHistory();
    }

    /** Current speed in blocks per second, used to stretch the tail while flying. */
    float speed() {
        return (float) Math.sqrt(this.vx * this.vx + this.vy * this.vy + this.vz * this.vz);
    }

    int historyCount() {
        return this.historyCount;
    }

    /**
     * Catmull-Rom sample of the tail. {@code t} is a floating history index where 0 is the head
     * (newest sample) and {@code historyCount() - 1} is the oldest. Sampling the spline instead
     * of the raw samples is what removes the visible beads from the trail.
     */
    void sample(float t, float[] out) {
        int count = this.historyCount;
        if (count == 0) {
            out[0] = (float) this.x;
            out[1] = (float) this.y;
            out[2] = (float) this.z;
            return;
        }
        if (count < 4) {
            read(Math.round(Mth.clamp(t, 0.0F, count - 1)), out);
            return;
        }

        float clamped = Mth.clamp(t, 0.0F, count - 1);
        int i1 = (int) clamped;
        float f = clamped - i1;
        int i0 = Math.max(0, i1 - 1);
        int i2 = Math.min(count - 1, i1 + 1);
        int i3 = Math.min(count - 1, i1 + 2);

        float[] p0 = TEMP_A;
        float[] p1 = TEMP_B;
        float[] p2 = TEMP_C;
        float[] p3 = TEMP_D;
        read(i0, p0);
        read(i1, p1);
        read(i2, p2);
        read(i3, p3);

        for (int axis = 0; axis < 3; axis++) {
            out[axis] = catmullRom(p0[axis], p1[axis], p2[axis], p3[axis], f);
        }
    }

    private static final float[] TEMP_A = new float[3];
    private static final float[] TEMP_B = new float[3];
    private static final float[] TEMP_C = new float[3];
    private static final float[] TEMP_D = new float[3];

    private void read(int age, float[] out) {
        int slot = this.historyHead - 1 - age;
        while (slot < 0) {
            slot += HISTORY;
        }
        slot %= HISTORY;
        int base = slot * 3;
        out[0] = this.history[base];
        out[1] = this.history[base + 1];
        out[2] = this.history[base + 2];
    }

    private void pushHistory() {
        int base = this.historyHead * 3;
        this.history[base] = (float) this.x;
        this.history[base + 1] = (float) this.y;
        this.history[base + 2] = (float) this.z;
        this.historyHead = (this.historyHead + 1) % HISTORY;
        if (this.historyCount < HISTORY) {
            this.historyCount++;
        }
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5F * ((2.0F * p1)
                + (-p0 + p2) * t
                + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t2
                + (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t3);
    }

    private static float hash(int seed, int salt) {
        int hash = seed * 0x9E3779B9 + salt * 0x85EBCA6B;
        hash ^= hash >>> 15;
        hash *= 0x2C1B3C6D;
        hash ^= hash >>> 12;
        return ((hash >>> 8) & 0xFFFF) / 65535.0F;
    }
}
