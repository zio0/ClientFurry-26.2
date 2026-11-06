package win.furryhook.pve;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class PveAutomationCoordinatorTest {
    private final PveAutomationCoordinator coordinator = PveAutomationCoordinator.INSTANCE;

    @AfterEach
    void resetCoordinator() {
        this.coordinator.revokeAll(PveAutomationCoordinator.RevocationReason.SHUTDOWN);
    }

    @Test
    void claimsAllResourcesAtomically() {
        Owner owner = new Owner();

        assertTrue(this.coordinator.acquire(
                owner,
                AutomationPriority.FEATURE,
                EnumSet.of(AutomationResource.MOVEMENT, AutomationResource.ROTATION)
        ));
        assertTrue(this.coordinator.owns(owner, AutomationResource.MOVEMENT));
        assertTrue(this.coordinator.owns(owner, AutomationResource.ROTATION));
    }

    @Test
    void equalPriorityCannotStealClaim() {
        Owner first = new Owner();
        Owner second = new Owner();
        assertTrue(this.coordinator.acquire(
                first,
                AutomationPriority.FEATURE,
                EnumSet.of(AutomationResource.INVENTORY)
        ));

        assertFalse(this.coordinator.acquire(
                second,
                AutomationPriority.FEATURE,
                EnumSet.of(AutomationResource.INVENTORY, AutomationResource.SCREEN)
        ));
        assertFalse(this.coordinator.owns(second, AutomationResource.SCREEN));
        assertNull(second.reason);
    }

    @Test
    void higherPriorityPreemptsAndNotifiesOwner() {
        Owner background = new Owner();
        Owner bot = new Owner();
        assertTrue(this.coordinator.acquire(
                background,
                AutomationPriority.BACKGROUND,
                EnumSet.of(AutomationResource.CHAT)
        ));

        assertTrue(this.coordinator.acquire(
                bot,
                AutomationPriority.BOT,
                EnumSet.of(AutomationResource.CHAT)
        ));
        assertEquals(PveAutomationCoordinator.RevocationReason.PREEMPTED, background.reason);
        assertTrue(this.coordinator.owns(bot, AutomationResource.CHAT));
    }

    @Test
    void explicitActivationCanReplaceEqualPriorityOwner() {
        Owner oldBot = new Owner();
        Owner newBot = new Owner();
        assertTrue(this.coordinator.acquire(
                oldBot,
                AutomationPriority.BOT,
                EnumSet.of(AutomationResource.MOVEMENT, AutomationResource.NAVIGATION)
        ));

        assertTrue(this.coordinator.acquire(
                newBot,
                AutomationPriority.BOT,
                EnumSet.of(AutomationResource.MOVEMENT, AutomationResource.NAVIGATION),
                true
        ));

        assertEquals(PveAutomationCoordinator.RevocationReason.PREEMPTED, oldBot.reason);
        assertTrue(this.coordinator.owns(newBot, AutomationResource.MOVEMENT));
        assertFalse(this.coordinator.owns(oldBot, AutomationResource.NAVIGATION));
    }

    @Test
    void canReleaseOneDynamicResourceWithoutDroppingOthers() {
        Owner owner = new Owner();
        assertTrue(this.coordinator.acquire(
                owner,
                AutomationPriority.BOT,
                EnumSet.of(AutomationResource.INVENTORY, AutomationResource.SCREEN)
        ));

        this.coordinator.release(owner, EnumSet.of(AutomationResource.SCREEN));

        assertTrue(this.coordinator.owns(owner, AutomationResource.INVENTORY));
        assertFalse(this.coordinator.isClaimed(AutomationResource.SCREEN));
    }

    private static final class Owner implements AutomationOwner {
        private PveAutomationCoordinator.RevocationReason reason;

        @Override
        public void onAutomationRevoked(PveAutomationCoordinator.RevocationReason reason) {
            this.reason = reason;
        }
    }
}
