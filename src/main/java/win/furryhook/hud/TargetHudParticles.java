package win.furryhook.hud;

import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.gui.Render2DUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Small 2D particle pool used by the target HUD: sparks shed off the health bar as the target's
 * health drains, and a burst off the target's head every time it takes a hit.
 *
 * <p>Everything is screen-space and frame-time driven, so it stays in step with the panel it is
 * drawn over regardless of the game's tick rate. Particles are drawn as two rounded quads - a
 * wide, faint halo and a bright core - which reads as a glow without needing a blur pass.
 */
public final class TargetHudParticles {

    private static final int MAX_PARTICLES = 220;
    /** Damage (in half-hearts) that already saturates the emitted particle count. */
    private static final float FULL_HIT = 8.0F;

    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private long lastUpdate;

    private static final class Particle {
        float x;
        float y;
        float velocityX;
        float velocityY;
        float size;
        float gravity;
        float drag;
        float life;
        float maxLife;
        int color;
    }

    /** Advances every live particle and retires the expired ones. Call once per frame. */
    public void update() {
        long now = System.currentTimeMillis();
        float delta = this.lastUpdate == 0L ? 0.016F : Math.min(80L, now - this.lastUpdate) / 1000.0F;
        this.lastUpdate = now;
        if (this.particles.isEmpty()) {
            return;
        }
        for (int i = this.particles.size() - 1; i >= 0; i--) {
            Particle particle = this.particles.get(i);
            particle.life -= delta;
            if (particle.life <= 0.0F) {
                this.particles.remove(i);
                continue;
            }
            particle.velocityY += particle.gravity * delta;
            float damping = Math.max(0.0F, 1.0F - particle.drag * delta);
            particle.velocityX *= damping;
            particle.velocityY *= damping;
            particle.x += particle.velocityX * delta;
            particle.y += particle.velocityY * delta;
        }
    }

    public void clear() {
        this.particles.clear();
    }

    public boolean isEmpty() {
        return this.particles.isEmpty();
    }

    /**
     * Sheds sparks off the edge of a vertical health bar - the strip of bar that just disappeared.
     * They spill sideways off the bar and fall.
     *
     * @param barX     left edge of the bar
     * @param barWidth width of the bar
     * @param edgeY    top of the remaining fill, i.e. where the health just drained from
     * @param count    how many sparks to shed - see {@link #countFor}
     */
    public void emitHealthVertical(float barX, float barWidth, float edgeY, float unit, int color, int count) {
        for (int i = 0; i < count; i++) {
            Particle particle = spawn(color, unit);
            if (particle == null) {
                return;
            }
            particle.x = barX + this.random.nextFloat() * barWidth;
            particle.y = edgeY + (this.random.nextFloat() - 0.5F) * 1.5F * unit;
            particle.velocityX = (this.random.nextFloat() - 0.5F) * 26.0F * unit;
            particle.velocityY = (2.0F + this.random.nextFloat() * 16.0F) * unit;
            particle.gravity = 52.0F * unit;
            particle.drag = 1.6F;
            particle.size = (0.5F + this.random.nextFloat() * 0.9F) * unit;
            particle.maxLife = 0.32F + this.random.nextFloat() * 0.38F;
            particle.life = particle.maxLife;
        }
    }

    /**
     * Sheds sparks off the right edge of a horizontal health bar - used by the Gsmar-style bars.
     *
     * @param edgeX right edge of the remaining fill
     */
    public void emitHealthHorizontal(float edgeX, float barY, float barHeight, float unit, int color, int count) {
        for (int i = 0; i < count; i++) {
            Particle particle = spawn(color, unit);
            if (particle == null) {
                return;
            }
            particle.x = edgeX + (this.random.nextFloat() - 0.5F) * 1.5F * unit;
            particle.y = barY + this.random.nextFloat() * barHeight;
            particle.velocityX = (3.0F + this.random.nextFloat() * 20.0F) * unit;
            particle.velocityY = (this.random.nextFloat() - 0.6F) * 20.0F * unit;
            particle.gravity = 58.0F * unit;
            particle.drag = 1.8F;
            particle.size = (0.5F + this.random.nextFloat() * 0.9F) * unit;
            particle.maxLife = 0.30F + this.random.nextFloat() * 0.36F;
            particle.life = particle.maxLife;
        }
    }

    /**
     * Radial burst out of the target's head icon. Particles start on a ring just inside the head
     * so the head itself stays readable, then fly outwards and settle under gravity.
     */
    public void emitHeadBurst(float headX, float headY, float headSize, float unit, int color, int count) {
        float centerX = headX + headSize * 0.5F;
        float centerY = headY + headSize * 0.5F;
        float ring = headSize * 0.34F;
        for (int i = 0; i < count; i++) {
            Particle particle = spawn(color, unit);
            if (particle == null) {
                return;
            }
            double angle = this.random.nextDouble() * Math.PI * 2.0;
            float radius = ring * (0.6F + this.random.nextFloat() * 0.6F);
            float dirX = (float) Math.cos(angle);
            float dirY = (float) Math.sin(angle);
            float speed = (26.0F + this.random.nextFloat() * 40.0F) * unit;
            particle.x = centerX + dirX * radius;
            particle.y = centerY + dirY * radius;
            particle.velocityX = dirX * speed;
            particle.velocityY = dirY * speed - 8.0F * unit;
            particle.gravity = 74.0F * unit;
            particle.drag = 3.4F;
            particle.size = (0.6F + this.random.nextFloat() * 1.1F) * unit;
            particle.maxLife = 0.34F + this.random.nextFloat() * 0.42F;
            particle.life = particle.maxLife;
        }
    }

    /** Draws every live particle, multiplied by the panel's own fade alpha. */
    public void draw(float alpha) {
        if (this.particles.isEmpty() || alpha <= 0.01F) {
            return;
        }
        for (Particle particle : this.particles) {
            float progress = Math.clamp(particle.life / particle.maxLife, 0.0F, 1.0F);
            // Ease out so sparks hold their brightness for most of the flight, then drop off.
            float fade = progress * progress * (3.0F - 2.0F * progress);
            float size = particle.size * (0.45F + 0.55F * progress);
            if (size <= 0.05F) {
                continue;
            }
            float halo = size * 2.6F;
            Render2DUtil.rect(particle.x - halo * 0.5F, particle.y - halo * 0.5F, halo, halo)
                    .color(ColorUtil.multiplyAlpha(particle.color, alpha * fade * 0.22F))
                    .radius(halo * 0.5F)
                    .draw();
            Render2DUtil.rect(particle.x - size * 0.5F, particle.y - size * 0.5F, size, size)
                    .color(ColorUtil.multiplyAlpha(particle.color, alpha * fade))
                    .radius(size * 0.5F)
                    .draw();
        }
    }

    /** Scales a spark count between {@code min} and {@code max} by how hard the hit landed. */
    public static int countFor(float damage, int min, int max) {
        float scaled = Math.clamp(damage / FULL_HIT, 0.0F, 1.0F);
        return Math.round(min + (max - min) * scaled);
    }

    /**
     * Allocates a particle, tinting a share of them towards white so the burst has hot cores
     * instead of reading as one flat colour. Returns null once the pool is full.
     */
    private Particle spawn(int color, float unit) {
        if (this.particles.size() >= MAX_PARTICLES || unit <= 0.0F) {
            return null;
        }
        Particle particle = new Particle();
        particle.color = this.random.nextFloat() < 0.28F
                ? ColorUtil.lerp(color, ColorUtil.WHITE, 0.55F)
                : color;
        this.particles.add(particle);
        return particle;
    }
}
