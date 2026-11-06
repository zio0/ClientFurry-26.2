package win.furryhook.pve.mining;

public final class BaseFinderFsm {
    public enum State {
        IDLE,
        SERVER_TRANSFER,
        DESCENDING,
        SEARCHING,
        BYPASSING,
        FOUND,
        PAUSED,
        ERROR
    }

    public enum Signal {
        START,
        TRANSFER_DONE,
        DESCENT_DONE,
        PATH_STUCK,
        BYPASS_DONE,
        CLUSTER_FOUND,
        UNSAFE,
        FAIL,
        STOP
    }

    private BaseFinderFsm() {
    }

    public static State next(State state, Signal signal, boolean usesServerTransfer) {
        if (signal == Signal.STOP) {
            return State.IDLE;
        }
        if (signal == Signal.FAIL) {
            return State.ERROR;
        }
        if (signal == Signal.CLUSTER_FOUND
                && state != State.IDLE && state != State.ERROR) {
            return State.FOUND;
        }
        if (signal == Signal.UNSAFE
                && state != State.IDLE && state != State.FOUND && state != State.ERROR) {
            return State.PAUSED;
        }

        return switch (state) {
            case IDLE -> signal == Signal.START
                    ? (usesServerTransfer ? State.SERVER_TRANSFER : State.DESCENDING)
                    : state;
            case SERVER_TRANSFER -> signal == Signal.TRANSFER_DONE
                    ? State.DESCENDING
                    : state;
            case DESCENDING -> {
                if (signal == Signal.DESCENT_DONE) {
                    yield State.SEARCHING;
                }
                yield signal == Signal.PATH_STUCK ? State.BYPASSING : state;
            }
            case SEARCHING -> signal == Signal.PATH_STUCK
                    ? State.BYPASSING
                    : state;
            case BYPASSING -> signal == Signal.BYPASS_DONE
                    ? State.SEARCHING
                    : state;
            case FOUND, PAUSED, ERROR -> state;
        };
    }

    public static State resume(State desired, boolean usesServerTransfer) {
        if (desired == null || desired == State.IDLE || desired == State.PAUSED
                || desired == State.ERROR || desired == State.FOUND) {
            return usesServerTransfer ? State.SERVER_TRANSFER : State.DESCENDING;
        }
        return desired;
    }
}
