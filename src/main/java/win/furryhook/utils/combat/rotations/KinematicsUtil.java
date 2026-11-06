package win.furryhook.utils.combat.rotations;

import net.minecraft.util.Mth;

public class KinematicsUtil {

    /**
     * Calculates the movement time (in milliseconds) based on Fitts's Law.
     * Formula: T = a + b * log2(distance / width + 1)
     *
     * @param distance Distance to move (e.g. difference in yaw/pitch degrees)
     * @param width Size of the target (e.g. angular size of the hitbox in degrees)
     * @param a Base time (constant delay)
     * @param b Scaling factor
     * @return Time in milliseconds
     */
    public static long calculateFittsLawTime(double distance, double width, double a, double b) {
        if (width <= 0) width = 0.1;
        double indexOfDifficulty = Math.log((distance / width) + 1.0) / Math.log(2.0);
        if (indexOfDifficulty < 0) indexOfDifficulty = 0;
        return (long) (a + b * indexOfDifficulty);
    }

    /**
     * Calculates Minimum Jerk Trajectory position at normalized time t (0.0 to 1.0).
     * Generates a bell-shaped velocity profile which is typical for human movements.
     * Formula: x(t) = xi + (xf - xi) * (10t^3 - 15t^4 + 6t^5)
     */
    public static double getMinimumJerk(double start, double end, double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        double t3 = t * t * t;
        double t4 = t3 * t;
        double t5 = t4 * t;
        double factor = 10 * t3 - 15 * t4 + 6 * t5;
        return start + (end - start) * factor;
    }
}
