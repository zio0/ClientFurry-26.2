package win.furryhook.pve;

import net.minecraft.client.Minecraft;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureEnableRejectedException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public abstract class PveFeature extends Feature implements AutomationOwner {
    private final AutomationPriority priority;
    private final EnumSet<AutomationResource> baseResources;

    protected PveFeature(String name,
                         String description,
                         FeatureCategory category,
                         int bind,
                         AutomationPriority priority,
                         AutomationResource... resources) {
        super(name, description, category, bind);
        this.priority = priority;
        this.baseResources = resources.length == 0
                ? EnumSet.noneOf(AutomationResource.class)
                : EnumSet.copyOf(Arrays.asList(resources));
    }

    @Override
    protected final void onEnable() {
        validatePveEnable();
        if (!PveAutomationCoordinator.INSTANCE.acquire(
                this,
                this.priority,
                this.baseResources,
                true
        )) {
            String owner = busyOwner();
            throw new FeatureEnableRejectedException(
                    owner == null
                            ? "automation resources are temporarily busy"
                            : "resource is held by " + owner
            );
        }
        try {
            onPveEnable();
        } catch (RuntimeException | Error throwable) {
            PveAutomationCoordinator.INSTANCE.release(this);
            throw throwable;
        }
    }

    @Override
    protected final void onDisable() {
        try {
            onPveDisable();
        } finally {
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    protected final boolean claim(AutomationResource first,
                                  AutomationResource... rest) {
        return PveAutomationCoordinator.INSTANCE.acquire(
                this,
                this.priority,
                first,
                rest
        );
    }

    protected final boolean owns(AutomationResource resource) {
        return PveAutomationCoordinator.INSTANCE.owns(this, resource);
    }

    protected final void release(AutomationResource first,
                                 AutomationResource... rest) {
        PveAutomationCoordinator.INSTANCE.release(this, EnumSet.of(first, rest));
    }

    protected final Set<AutomationResource> baseResources() {
        return Set.copyOf(this.baseResources);
    }

    protected void onPveEnable() {
    }

    protected void validatePveEnable() {
    }

    protected void onPveDisable() {
    }

    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
    }

    protected boolean disableAfterRevocation(
            PveAutomationCoordinator.RevocationReason reason
    ) {
        return true;
    }

    private String busyOwner() {
        for (AutomationResource resource : this.baseResources) {
            String owner = PveAutomationCoordinator.INSTANCE.ownerId(resource);
            if (owner != null && !owner.equals(automationId())) {
                return owner;
            }
        }
        return null;
    }

    @Override
    public final void onAutomationRevoked(PveAutomationCoordinator.RevocationReason reason) {
        Minecraft client = Minecraft.getInstance();
        Runnable revoke = () -> {
            onPvePreempted(reason);
            if (disableAfterRevocation(reason) && isEnabled()) {
                setEnabled(false);
            }
        };
        if (client.isSameThread()) {
            revoke.run();
        } else {
            client.execute(revoke);
        }
    }
}
