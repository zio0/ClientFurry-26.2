package win.furryhook.pve.mining;

public final class AutoMineFsm {
    public enum State {
        IDLE,
        WARPING,
        ROUTING,
        MINING,
        TO_DEPOSIT,
        OPENING_DEPOSIT,
        DEPOSITING,
        RETURNING,
        PAUSED,
        ERROR
    }

    public enum Signal {
        START,
        TELEPORT_DONE,
        ARRIVED,
        INVENTORY_FULL,
        CONTAINER_OPEN,
        DEPOSIT_DONE,
        UNSAFE,
        FAIL,
        STOP
    }

    private AutoMineFsm() {
    }

    public static State next(
            State state,
            Signal signal,
            boolean hasRoute,
            boolean hasDeposit,
            boolean usesServerWarp
    ) {
        if (signal == Signal.STOP) {
            return State.IDLE;
        }
        if (signal == Signal.FAIL) {
            return State.ERROR;
        }
        if (signal == Signal.UNSAFE && state != State.IDLE && state != State.ERROR) {
            return State.PAUSED;
        }

        return switch (state) {
            case IDLE -> signal == Signal.START
                    ? startState(hasRoute, usesServerWarp)
                    : state;
            case WARPING -> signal == Signal.TELEPORT_DONE
                    ? (hasRoute ? State.ROUTING : State.MINING)
                    : state;
            case ROUTING -> signal == Signal.ARRIVED ? State.MINING : state;
            case MINING -> signal == Signal.INVENTORY_FULL
                    ? (hasDeposit ? State.TO_DEPOSIT : State.MINING)
                    : state;
            case TO_DEPOSIT -> signal == Signal.ARRIVED ? State.OPENING_DEPOSIT : state;
            case OPENING_DEPOSIT -> signal == Signal.CONTAINER_OPEN
                    ? State.DEPOSITING
                    : state;
            case DEPOSITING -> signal == Signal.DEPOSIT_DONE
                    ? restartState(hasRoute, usesServerWarp)
                    : state;
            case RETURNING -> signal == Signal.ARRIVED ? State.MINING : state;
            case PAUSED, ERROR -> state;
        };
    }

    public static State resume(
            State desiredState,
            boolean hasRoute,
            boolean usesServerWarp
    ) {
        if (desiredState == null || desiredState == State.PAUSED
                || desiredState == State.ERROR || desiredState == State.IDLE) {
            return startState(hasRoute, usesServerWarp);
        }
        return desiredState;
    }

    private static State startState(boolean hasRoute, boolean usesServerWarp) {
        if (usesServerWarp) {
            return State.WARPING;
        }
        return hasRoute ? State.ROUTING : State.MINING;
    }

    private static State restartState(boolean hasRoute, boolean usesServerWarp) {
        if (usesServerWarp) {
            return State.WARPING;
        }
        return hasRoute ? State.RETURNING : State.MINING;
    }
}
