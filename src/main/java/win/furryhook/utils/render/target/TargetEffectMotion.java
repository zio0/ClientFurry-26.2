package win.furryhook.utils.render.target;

import net.minecraft.util.Mth;

final class TargetEffectMotion {
    private TargetEffectMotion() {
    }

    static float assembly(float globalProgress, int index, int count) {
        float position = count <= 1 ? 0.0F : index / (float) (count - 1);
        float delay = position * 0.22F;
        float progress = Mth.clamp((globalProgress - delay) / (1.0F - delay), 0.0F, 1.0F);
        return progress * progress * (3.0F - 2.0F * progress);
    }

    static double scatter(int seed, int axis, double distance, float assembly) {
        int hash = seed * 0x9E3779B9 + axis * 0x7F4A7C15;
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        float signed = ((hash >>> 8) & 0xFFFF) / 32767.5F - 1.0F;
        return signed * distance * (1.0F - assembly);
    }
}
