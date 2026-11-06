package win.furryhook.feature.impl.pve.autowarden;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWardenWorkflowTest {
    @Test
    void completesLootAndStorageCycle() {
        AutoWardenWorkflow workflow = new AutoWardenWorkflow();

        assertTrue(workflow.start(true, false, 1L));
        assertTrue(workflow.anarchyReady(2L));
        assertTrue(workflow.cityReady(3L));
        assertTrue(workflow.chestSelected(20, 180, 4L));
        assertTrue(workflow.chestReached(5L));
        assertTrue(workflow.requestStorage("inventory full", 6L));
        assertTrue(workflow.storageReady(7L));
        assertTrue(workflow.depositFinished(true, true, 8L));
        assertTrue(workflow.restockFinished(true, 9L));
        assertTrue(workflow.sellFinished(10L));
        assertEquals(AutoWardenWorkflow.Phase.IDLE, workflow.phase());
    }

    @Test
    void longTimerUsesSafeHomeWaitPath() {
        AutoWardenWorkflow workflow = new AutoWardenWorkflow();
        workflow.start(true, false, 1L);
        workflow.anarchyReady(2L);
        workflow.cityReady(3L);

        assertTrue(workflow.chestSelected(400, 180, 4L));
        assertEquals(AutoWardenWorkflow.Phase.HOME_WAIT, workflow.phase());
        assertTrue(workflow.returnWindowReached(5L));
        assertEquals(AutoWardenWorkflow.Phase.RETURN_TO_CITY, workflow.phase());
    }

    @Test
    void rejectsInvalidTransitionWithoutCorruptingState() {
        AutoWardenWorkflow workflow = new AutoWardenWorkflow();

        assertFalse(workflow.chestReached(1L));
        assertEquals(AutoWardenWorkflow.Phase.IDLE, workflow.phase());
    }
}
