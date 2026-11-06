package win.furryhook.pve.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningStateMachinesTest {
    @Test
    void autoMineCompletesRouteDepositAndReturnCycle() {
        AutoMineFsm.State state = AutoMineFsm.next(
                AutoMineFsm.State.IDLE,
                AutoMineFsm.Signal.START,
                true,
                true,
                false
        );
        assertEquals(AutoMineFsm.State.ROUTING, state);

        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.ARRIVED, true, true, false
        );
        assertEquals(AutoMineFsm.State.MINING, state);

        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.INVENTORY_FULL, true, true, false
        );
        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.ARRIVED, true, true, false
        );
        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.CONTAINER_OPEN, true, true, false
        );
        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.DEPOSIT_DONE, true, true, false
        );
        assertEquals(AutoMineFsm.State.RETURNING, state);

        state = AutoMineFsm.next(
                state, AutoMineFsm.Signal.ARRIVED, true, true, false
        );
        assertEquals(AutoMineFsm.State.MINING, state);
    }

    @Test
    void autoMineWithoutDepositContinuesMining() {
        assertEquals(
                AutoMineFsm.State.MINING,
                AutoMineFsm.next(
                        AutoMineFsm.State.MINING,
                        AutoMineFsm.Signal.INVENTORY_FULL,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    void baseFinderUsesBypassThenStopsOnCluster() {
        BaseFinderFsm.State state = BaseFinderFsm.next(
                BaseFinderFsm.State.IDLE,
                BaseFinderFsm.Signal.START,
                false
        );
        assertEquals(BaseFinderFsm.State.DESCENDING, state);

        state = BaseFinderFsm.next(
                state, BaseFinderFsm.Signal.DESCENT_DONE, false
        );
        state = BaseFinderFsm.next(
                state, BaseFinderFsm.Signal.PATH_STUCK, false
        );
        assertEquals(BaseFinderFsm.State.BYPASSING, state);

        state = BaseFinderFsm.next(
                state, BaseFinderFsm.Signal.BYPASS_DONE, false
        );
        state = BaseFinderFsm.next(
                state, BaseFinderFsm.Signal.CLUSTER_FOUND, false
        );
        assertEquals(BaseFinderFsm.State.FOUND, state);
    }
}
