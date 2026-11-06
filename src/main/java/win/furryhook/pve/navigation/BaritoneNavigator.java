package win.furryhook.pve.navigation;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class BaritoneNavigator implements Navigator {
    public static final BaritoneNavigator INSTANCE = new BaritoneNavigator();

    private SettingsSnapshot snapshot;
    private BlockPos currentGoal;
    private boolean active;

    private BaritoneNavigator() {
    }

    @Override
    public boolean isAvailable() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone() != null;
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public synchronized void begin(NavigationOptions options) {
        Objects.requireNonNull(options, "options");
        Settings settings = BaritoneAPI.getSettings();
        if (!this.active) {
            this.snapshot = SettingsSnapshot.capture(settings);
            this.active = true;
        }
        settings.allowBreak.value = options.allowBreak();
        settings.allowPlace.value = options.allowPlace();
        settings.allowSprint.value = options.allowSprint();
        settings.freeLook.value = !options.rotateView();
        settings.blockFreeLook.value = !options.rotateView();
        settings.chatControl.value = false;
        settings.chatControlAnyway.value = false;
    }

    @Override
    public synchronized void pathTo(BlockPos position, int radius) {
        requireActive();
        this.currentGoal = Objects.requireNonNull(position, "position").immutable();
        baritone().getCustomGoalProcess().setGoalAndPath(
                new GoalNear(this.currentGoal, Math.max(0, radius))
        );
    }

    // Заглушки для методов копания, чтобы не ломать остальной код проекта
    @Override
    public synchronized void mine(int quantity, net.minecraft.world.level.block.Block... blocks) {
        // Копание отключено в этой версии
    }

    @Override
    public synchronized void setMineBounds(BlockPos min, BlockPos max) {
        // Регионы копания отключены
    }

    @Override
    public synchronized void setMineRenderColor(int argb) {
        // Цвета отключены
    }

    @Override
    public synchronized void setMineAoeLevel(int level) {
        // AoE отключено
    }

    @Override
    public boolean isPathing() {
        return isAvailable() && baritone().getPathingBehavior().isPathing();
    }

    @Override
    public boolean isMining() {
        return false; // Копание отключено
    }

    @Override
    public java.util.List<BlockPos> miningTargets() {
        return java.util.List.of(); // Список целей пуст
    }

    @Override
    public Optional<Double> estimatedTicksToGoal() {
        if (!isAvailable()) {
            return Optional.empty();
        }
        return baritone().getPathingBehavior().estimatedTicksToGoal();
    }

    @Override
    public synchronized Optional<BlockPos> currentGoal() {
        return Optional.ofNullable(this.currentGoal);
    }

    @Override
    public synchronized String diagnostics() {
        if (!isAvailable()) {
            return "available=false";
        }
        Settings settings = BaritoneAPI.getSettings();
        IBaritone baritone = baritone();
        return String.format(
                Locale.ROOT,
                "available=true session=%s pathing=%s hasPath=%s "
                        + "calculating=%s goal=%s customGoal=%s allowBreak=%s "
                        + "allowPlace=%s sprint=%s freeLook=%s blockFreeLook=%s",
                this.active,
                baritone.getPathingBehavior().isPathing(),
                baritone.getPathingBehavior().hasPath(),
                baritone.getPathingBehavior().getInProgress().isPresent(),
                baritone.getPathingBehavior().getGoal(),
                this.currentGoal,
                settings.allowBreak.value,
                settings.allowPlace.value,
                settings.allowSprint.value,
                settings.freeLook.value,
                settings.blockFreeLook.value
        );
    }

    @Override
    public synchronized void cancel() {
        if (isAvailable()) {
            baritone().getPathingBehavior().cancelEverything();
        }
        this.currentGoal = null;
    }

    @Override
    public synchronized void end() {
        cancel();
        if (this.snapshot != null) {
            this.snapshot.restore(BaritoneAPI.getSettings());
        }
        this.snapshot = null;
        this.active = false;
    }

    private void requireActive() {
        if (!this.active) {
            throw new IllegalStateException("Navigation session has not started");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("Baritone is unavailable");
        }
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private record SettingsSnapshot(
            boolean allowBreak,
            boolean allowPlace,
            boolean allowSprint,
            boolean freeLook,
            boolean blockFreeLook,
            boolean chatControl,
            boolean chatControlAnyway
    ) {
        private static SettingsSnapshot capture(Settings settings) {
            return new SettingsSnapshot(
                    settings.allowBreak.value,
                    settings.allowPlace.value,
                    settings.allowSprint.value,
                    settings.freeLook.value,
                    settings.blockFreeLook.value,
                    settings.chatControl.value,
                    settings.chatControlAnyway.value
            );
        }

        private void restore(Settings settings) {
            settings.allowBreak.value = this.allowBreak;
            settings.allowPlace.value = this.allowPlace;
            settings.allowSprint.value = this.allowSprint;
            settings.freeLook.value = this.freeLook;
            settings.blockFreeLook.value = this.blockFreeLook;
            settings.chatControl.value = this.chatControl;
            settings.chatControlAnyway.value = this.chatControlAnyway;
        }
    }
}