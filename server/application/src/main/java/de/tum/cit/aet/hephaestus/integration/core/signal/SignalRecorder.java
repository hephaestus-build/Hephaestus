package de.tum.cit.aet.hephaestus.integration.core.signal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface SignalRecorder {
    /**
     * Record or claim an undecided occasion. Claimed occasions must be settled in the same transaction.
     *
     * @return true iff this call now owns the signal
     */
    default boolean record(SignalKey key, Instant occurredAt, DiscoveredVia discoveredVia) {
        return record(key, occurredAt, discoveredVia, null);
    }

    /**
     * Record an occasion with its requester so per-person quota checks include it immediately.
     *
     * @param requestedByUserId the SCM user who asked, or null when nobody did (an event, sync or campaign
     *     discovery)
     */
    boolean record(SignalKey key, Instant occurredAt, DiscoveredVia discoveredVia, @Nullable Long requestedByUserId);

    /**
     * Queue a live occasion in the caller's transaction without submitting a review. Existing queued
     * or triggered content is unchanged; undecided sync discoveries and coalesced content may be queued.
     *
     * @return whether this call queued content
     */
    default boolean defer(SignalKey key, Instant occurredAt) {
        return defer(key, occurredAt, null);
    }

    /** Queue an issue update with the initiator, if the webhook named one. */
    boolean defer(SignalKey key, Instant occurredAt, @Nullable Long actorUserId);

    /** Link the occasion to its admitted job. */
    void markTriggered(SignalKey key, UUID jobId);

    /**
     * Refuse admission using the state determined by {@link SignalStateReason#resultingState()}.
     */
    void markRefused(SignalKey key, SignalStateReason reason);
}
