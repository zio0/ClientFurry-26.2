package win.furryhook.utils.combat;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;

public final class ServerTickSync {
    public static final ServerTickSync INSTANCE = new ServerTickSync();

    private static final float DEFAULT_TPS = 20.0F;
    private static final float MIN_TPS = 1.0F;
    private static final float SAMPLE_WEIGHT = 0.25F;
    private static final long SAMPLE_STALE_NANOS = 10_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private volatile float configuredTps = DEFAULT_TPS;
    private volatile float observedTps = DEFAULT_TPS;
    private volatile boolean frozen;
    private volatile int validSamples;
    private volatile long lastGameTime = Long.MIN_VALUE;
    private volatile long lastTimePacketNanos;
    private volatile long lastValidSampleNanos;

    private ServerTickSync() {
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE) {
            return;
        }
        if (event.getPacket() instanceof ClientboundTickingStatePacket packet) {
            this.configuredTps = Math.max(MIN_TPS, packet.tickRate());
            this.frozen = packet.isFrozen();
            if (this.validSamples == 0) {
                this.observedTps = this.configuredTps;
            }
            return;
        }
        if (event.getPacket() instanceof ClientboundSetTimePacket packet) {
            observeTimePacket(packet.gameTime(), System.nanoTime());
        }
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        reset();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        reset();
    }

    public float effectiveTps() {
        if (this.frozen) {
            return 0.0F;
        }
        long now = System.nanoTime();
        if (this.validSamples == 0 || now - this.lastValidSampleNanos > SAMPLE_STALE_NANOS) {
            return this.configuredTps;
        }
        return Math.clamp(this.observedTps, MIN_TPS, this.configuredTps);
    }

    public long tickNanos() {
        float tps = effectiveTps();
        return tps <= 0.0F
                ? Long.MAX_VALUE
                : Math.max(1L, Math.round(NANOS_PER_SECOND / (double) tps));
    }

    public double ticksUntilNextServerTick() {
        long tickNanos = tickNanos();
        if (tickNanos == Long.MAX_VALUE || this.lastTimePacketNanos == 0L) {
            return 0.0D;
        }
        long elapsed = Math.max(0L, System.nanoTime() - this.lastTimePacketNanos);
        long phase = elapsed % tickNanos;
        return (tickNanos - phase) / (double) tickNanos;
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void reset() {
        this.configuredTps = DEFAULT_TPS;
        this.observedTps = DEFAULT_TPS;
        this.frozen = false;
        this.validSamples = 0;
        this.lastGameTime = Long.MIN_VALUE;
        this.lastTimePacketNanos = 0L;
        this.lastValidSampleNanos = 0L;
    }

    private void observeTimePacket(long gameTime, long now) {
        if (this.lastGameTime != Long.MIN_VALUE && gameTime > this.lastGameTime) {
            long gameTicks = gameTime - this.lastGameTime;
            long elapsedNanos = now - this.lastTimePacketNanos;
            if (gameTicks <= 2_000L && elapsedNanos > 0L) {
                float sample = (float) (gameTicks * (double) NANOS_PER_SECOND / elapsedNanos);
                float upperBound = Math.max(MIN_TPS, this.configuredTps);
                if (sample >= MIN_TPS * 0.5F && sample <= upperBound * 1.25F) {
                    sample = Math.clamp(sample, MIN_TPS, upperBound);
                    this.observedTps = this.validSamples == 0
                            ? sample
                            : this.observedTps + (sample - this.observedTps) * SAMPLE_WEIGHT;
                    this.validSamples++;
                    this.lastValidSampleNanos = now;
                }
            }
        }
        this.lastGameTime = gameTime;
        this.lastTimePacketNanos = now;
    }
}
