package win.furryhook.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PveStateMachineTest {
    private enum State {
        IDLE,
        MOVING,
        DONE
    }

    @Test
    void tracksTransitionsAndElapsedTicks() {
        PveStateMachine<State> machine = new PveStateMachine<>(State.IDLE);

        assertTrue(machine.transition(State.MOVING, 10L));
        assertEquals(5L, machine.ticksInState(15L));
        assertEquals(1L, machine.transitionCount());
        assertFalse(machine.transition(State.MOVING, 16L));

        assertTrue(machine.transition(State.DONE, 20L));
        assertEquals(2L, machine.transitionCount());
        assertTrue(machine.is(State.DONE));
    }

    @Test
    void resetRestoresInitialState() {
        PveStateMachine<State> machine = new PveStateMachine<>(State.IDLE);
        machine.transition(State.MOVING, 4L);
        machine.reset(12L);

        assertEquals(State.IDLE, machine.state());
        assertEquals(12L, machine.enteredTick());
        assertEquals(0L, machine.transitionCount());
    }
}
