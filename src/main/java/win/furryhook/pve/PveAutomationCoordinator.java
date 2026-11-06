package win.furryhook.pve;

import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.lifecycle.ShutdownEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PveAutomationCoordinator {
    public static final PveAutomationCoordinator INSTANCE = new PveAutomationCoordinator();

    public enum RevocationReason {
        PREEMPTED,
        WORLD_CHANGE,
        DISCONNECT,
        SHUTDOWN
    }

    private final EnumMap<AutomationResource, Claim> claims =
            new EnumMap<>(AutomationResource.class);
    private final IdentityHashMap<AutomationOwner, EnumSet<AutomationResource>> owned =
            new IdentityHashMap<>();

    private PveAutomationCoordinator() {
    }

    public boolean acquire(AutomationOwner owner,
                           AutomationPriority priority,
                           Set<AutomationResource> resources) {
        return acquire(owner, priority, resources, false);
    }

    public boolean acquire(AutomationOwner owner,
                           AutomationPriority priority,
                           Set<AutomationResource> resources,
                           boolean preemptEqualPriority) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(resources, "resources");
        if (resources.isEmpty()) {
            return true;
        }

        Set<AutomationOwner> displaced =
                Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (this) {
            for (AutomationResource resource : resources) {
                Claim existing = this.claims.get(resource);
                if (existing != null
                        && existing.owner != owner
                        && (existing.priority.weight() > priority.weight()
                        || existing.priority.weight() == priority.weight()
                        && !preemptEqualPriority)) {
                    return false;
                }
            }

            for (AutomationResource resource : resources) {
                Claim existing = this.claims.put(resource, new Claim(owner, priority));
                if (existing != null && existing.owner != owner) {
                    removeOwned(existing.owner, resource);
                    displaced.add(existing.owner);
                }
                this.owned.computeIfAbsent(
                        owner,
                        ignored -> EnumSet.noneOf(AutomationResource.class)
                ).add(resource);
            }
        }

        displaced.forEach(displacedOwner ->
                displacedOwner.onAutomationRevoked(RevocationReason.PREEMPTED));
        return true;
    }

    public boolean acquire(AutomationOwner owner,
                           AutomationPriority priority,
                           AutomationResource first,
                           AutomationResource... rest) {
        EnumSet<AutomationResource> resources = EnumSet.of(first, rest);
        return acquire(owner, priority, resources);
    }

    public synchronized void release(AutomationOwner owner) {
        EnumSet<AutomationResource> resources = this.owned.remove(owner);
        if (resources == null) {
            return;
        }
        for (AutomationResource resource : resources) {
            Claim claim = this.claims.get(resource);
            if (claim != null && claim.owner == owner) {
                this.claims.remove(resource);
            }
        }
    }

    public synchronized void release(AutomationOwner owner,
                                     Set<AutomationResource> resources) {
        EnumSet<AutomationResource> ownedResources = this.owned.get(owner);
        if (ownedResources == null || resources.isEmpty()) {
            return;
        }
        for (AutomationResource resource : resources) {
            Claim claim = this.claims.get(resource);
            if (claim != null && claim.owner == owner) {
                this.claims.remove(resource);
                ownedResources.remove(resource);
            }
        }
        if (ownedResources.isEmpty()) {
            this.owned.remove(owner);
        }
    }

    public synchronized boolean owns(AutomationOwner owner, AutomationResource resource) {
        Claim claim = this.claims.get(resource);
        return claim != null && claim.owner == owner;
    }

    public synchronized boolean isClaimed(AutomationResource resource) {
        return this.claims.containsKey(resource);
    }

    public synchronized boolean isClaimedByOther(AutomationOwner owner,
                                                  AutomationResource resource) {
        Claim claim = this.claims.get(resource);
        return claim != null && claim.owner != owner;
    }

    public synchronized String ownerId(AutomationResource resource) {
        Claim claim = this.claims.get(resource);
        return claim == null ? null : claim.owner.automationId();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        revokeAll(RevocationReason.WORLD_CHANGE);
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        revokeAll(RevocationReason.DISCONNECT);
    }

    @EventTarget
    public void onShutdown(ShutdownEvent event) {
        revokeAll(RevocationReason.SHUTDOWN);
    }

    public void revokeAll(RevocationReason reason) {
        Set<AutomationOwner> owners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (this) {
            owners.addAll(this.owned.keySet());
            this.claims.clear();
            this.owned.clear();
        }
        owners.forEach(owner -> owner.onAutomationRevoked(reason));
    }

    private void removeOwned(AutomationOwner owner, AutomationResource resource) {
        EnumSet<AutomationResource> resources = this.owned.get(owner);
        if (resources == null) {
            return;
        }
        resources.remove(resource);
        if (resources.isEmpty()) {
            this.owned.remove(owner);
        }
    }

    private record Claim(AutomationOwner owner, AutomationPriority priority) {
    }
}
