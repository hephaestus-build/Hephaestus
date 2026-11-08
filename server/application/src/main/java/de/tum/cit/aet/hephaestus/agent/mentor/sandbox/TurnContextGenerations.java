package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation of each workspace's context on this worker. A change of what the workspace's turns may read (an
 * erasure, a consent change, a hidden repository) makes a new generation; so does a reconnect that may have missed
 * changes, for every workspace. A turn's built documents carry the generation they were built under and are not used
 * in a later one. The tools Pods are released on the same signal.
 */
public final class TurnContextGenerations {

    /** Raised for every workspace at once; the sum with a workspace's own count only grows. */
    private final AtomicLong all = new AtomicLong();

    private final Map<Long, AtomicLong> workspaces = new ConcurrentHashMap<>();

    public long current(long workspaceId) {
        AtomicLong own = workspaces.get(workspaceId);
        return all.get() + (own == null ? 0 : own.get());
    }

    public void changed(long workspaceId) {
        workspaces.computeIfAbsent(workspaceId, ignored -> new AtomicLong()).incrementAndGet();
    }

    public void changedAll() {
        all.incrementAndGet();
    }
}
