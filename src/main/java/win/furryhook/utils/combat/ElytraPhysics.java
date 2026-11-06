package win.furryhook.utils.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla elytra flight, reimplemented exactly, plus a forward simulation built on it.
 *
 * <p>Everything here is a transcription of {@code LivingEntity.updateFallFlyingMovement} as it is
 * compiled in this version — read off the disassembly, not from memory:
 *
 * <pre>
 * look      = getLookAngle();
 * pitchRad  = xRot * (pi/180);
 * horizLook = sqrt(look.x^2 + look.z^2);
 * horizSpeed= motion.horizontalDistance();
 * cosSq     = cos(pitchRad)^2;
 *
 * motion = motion.add(0, gravity * (-1 + cosSq * 0.75), 0);
 * if (motion.y &lt; 0 &amp;&amp; horizLook &gt; 0) {          // descending trades altitude for speed
 *     d = motion.y * -0.1 * cosSq;
 *     motion = motion.add(look.x * d / horizLook, d, look.z * d / horizLook);
 * }
 * if (pitchRad &lt; 0 &amp;&amp; horizLook &gt; 0) {          // looking up trades speed for lift
 *     d = horizSpeed * (-sin(pitchRad)) * 0.04;
 *     motion = motion.add(-look.x * d / horizLook, d * 3.2, -look.z * d / horizLook);
 * }
 * motion = motion.add((look.x / horizLook * horizSpeed - motion.x) * 0.1, 0,
 *                     (look.z / horizLook * horizSpeed - motion.z) * 0.1);
 * motion = motion.multiply(0.99, 0.98, 0.99);
 * </pre>
 *
 * <p>Two things fall out of that and both drive how an elytra chase has to be flown:
 *
 * <ul>
 *   <li>The heading only turns <b>10% per tick</b> toward where the head points. An elytra cannot
 *       corner; it can only start turning early. Aiming at where the target is now is always late.</li>
 *   <li>Pitching up is the only brake there is — {@code 0.04 * sin} off the horizontal speed, and
 *       the same energy comes back as lift. Nothing else in the tick sheds speed except the flat
 *       0.99 drag.</li>
 * </ul>
 *
 * <p>Because both of those are pure functions of the rotation the client sends, a chase flown with
 * them is a chase an anticheat that simulates elytra flight — Grim does, in full — predicts tick
 * for tick. No motion is written anywhere in this file: it only answers "where would this rotation
 * put me".
 */
public final class ElytraPhysics {

    public static final double HORIZONTAL_DRAG = 0.9900000095367432D;
    public static final double VERTICAL_DRAG = 0.9800000190734863D;
    /** Share of the gap between the current heading and the look heading closed each tick. */
    public static final double TURN_RATE = 0.1D;
    /** Player gravity with no modifiers; only used when an entity's own value is unavailable. */
    public static final double DEFAULT_GRAVITY = 0.08D;

    private ElytraPhysics() {
    }

    /** One tick of vanilla fall-flying movement. Returns the new velocity; writes nothing. */
    public static Vec3 step(Vec3 motion, float yaw, float pitch, double gravity) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double pitchRad = pitch * ((float) Math.PI / 180.0F);
        double horizLook = Math.sqrt(look.x * look.x + look.z * look.z);
        double horizSpeed = motion.horizontalDistance();
        double cosSq = Mth.square(Math.cos(pitchRad));

        Vec3 result = motion.add(0.0D, gravity * (-1.0D + cosSq * 0.75D), 0.0D);

        if (result.y < 0.0D && horizLook > 0.0D) {
            double gain = result.y * -0.1D * cosSq;
            result = result.add(look.x * gain / horizLook, gain, look.z * gain / horizLook);
        }

        if (pitchRad < 0.0D && horizLook > 0.0D) {
            double lift = horizSpeed * (-Math.sin(pitchRad)) * 0.04D;
            result = result.add(-look.x * lift / horizLook, lift * 3.2D, -look.z * lift / horizLook);
        }

        if (horizLook > 0.0D) {
            result = result.add(
                    (look.x / horizLook * horizSpeed - result.x) * TURN_RATE,
                    0.0D,
                    (look.z / horizLook * horizSpeed - result.z) * TURN_RATE
            );
        }

        return result.multiply(HORIZONTAL_DRAG, VERTICAL_DRAG, HORIZONTAL_DRAG);
    }

    /**
     * Result of a forward flight simulation.
     *
     * @param minDistance      closest the eye ever gets to the target's hitbox, in blocks
     * @param minDistanceTick  the tick that happens on
     * @param ticksInReach     how many simulated ticks the target is inside attack reach — the
     *                         number that decides whether a pass produces hits or a fly-by
     * @param firstReachTick   first tick inside reach, or -1
     * @param endSpeed         horizontal speed at the end of the simulation
     */
    public record Flight(double minDistance, int minDistanceTick, int ticksInReach, int firstReachTick, double endSpeed) {
        public boolean reaches() {
            return this.ticksInReach > 0;
        }
    }

    /**
     * Flies the current velocity forward under one aiming rule — every tick the head points at the
     * target's position carried {@code leadTicks} further along its own motion, with
     * {@code pitchOffset} added to the resulting pitch — and reports what that flight achieves.
     *
     * <p>Those two numbers are the whole steering vocabulary an elytra has, and each answers a
     * different failure. Lead answers "the target is crossing my path": with a 10% per tick turn
     * rate, pure pursuit curves in behind a crossing player and never catches up, while a lead aim
     * flies to where they are going. Pitch offset answers "I will arrive too fast to hit anything":
     * nose up is the only brake in the tick sheet, and bleeding speed on the way in is what turns a
     * one-tick fly-by into a window several ticks wide.
     */
    public static Flight simulate(
            Vec3 startPos,
            Vec3 startMotion,
            double eyeHeight,
            double gravity,
            LivingEntity target,
            Vec3 targetMotion,
            double reach,
            float pitchOffset,
            double leadTicks,
            int ticks
    ) {
        Vec3 pos = startPos;
        Vec3 motion = startMotion;
        Vec3 targetPos = target.position();
        double targetHeight = target.getBbHeight();
        double targetWidth = target.getBbWidth();
        double aimHeight = targetHeight * 0.75D;

        double minDistance = Double.MAX_VALUE;
        int minTick = 0;
        int ticksInReach = 0;
        int firstReachTick = -1;

        for (int tick = 1; tick <= ticks; tick++) {
            Vec3 eye = pos.add(0.0D, eyeHeight, 0.0D);
            Vec3 aim = targetPos.add(targetMotion.scale(leadTicks)).add(0.0D, aimHeight, 0.0D).subtract(eye);
            if (aim.lengthSqr() < 1.0E-8D) {
                break;
            }

            float yaw = (float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0D);
            float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z)));
            pitch = Mth.clamp(pitch + pitchOffset, -89.0F, 89.0F);

            motion = step(motion, yaw, pitch, gravity);
            pos = pos.add(motion);
            targetPos = targetPos.add(targetMotion);

            AABB box = new AABB(
                    targetPos.x - targetWidth * 0.5D, targetPos.y, targetPos.z - targetWidth * 0.5D,
                    targetPos.x + targetWidth * 0.5D, targetPos.y + targetHeight, targetPos.z + targetWidth * 0.5D
            );
            double distance = distanceToBox(box, pos.add(0.0D, eyeHeight, 0.0D));

            if (distance < minDistance) {
                minDistance = distance;
                minTick = tick;
            }
            if (distance <= reach) {
                ticksInReach++;
                if (firstReachTick < 0) {
                    firstReachTick = tick;
                }
            }
        }

        return new Flight(
                minDistance == Double.MAX_VALUE ? Double.MAX_VALUE : minDistance,
                minTick,
                ticksInReach,
                firstReachTick,
                motion.horizontalDistance()
        );
    }

    /** Distance from a point to the closest point of a box; zero inside it. */
    public static double distanceToBox(AABB box, Vec3 point) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
