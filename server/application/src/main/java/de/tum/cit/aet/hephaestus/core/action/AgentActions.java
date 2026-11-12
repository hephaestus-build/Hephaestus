package de.tum.cit.aet.hephaestus.core.action;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Enqueues effects on the outside world. The executor carries them out after the caller's transaction commits. */
@Service
@RequiredArgsConstructor
public class AgentActions {
    private final ActionStore store;

    /**
     * Enqueues the effect in the caller's transaction, if there is one, so it exists exactly when the caller's change
     * commits. The same
     * idempotency key returns the action already enqueued and changes nothing.
     */
    @Transactional
    public UUID enqueue(ActionRequest request) {
        return store.enqueue(request);
    }

    /**
     * Enqueues an action that is due only after {@code delay}, so that the caller that creates it can carry it out
     * first ({@link ActionExecutor#runNow(UUID, ActionAdapter)}). Empty when the key was already enqueued: then another caller owns it.
     */
    @Transactional
    public Optional<UUID> enqueueNew(ActionRequest request, Duration delay) {
        return store.insert(request, delay);
    }

    /** Makes a waiting action due within {@code delay}, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(long workspaceId, String idempotencyKey, Duration delay) {
        store.advance(workspaceId, idempotencyKey, delay);
    }

    /** The payload of an action that waits under {@code idempotencyKey}, before any attempt takes it. */
    @Transactional(readOnly = true)
    public Optional<JsonNode> admittedPayload(long workspaceId, String idempotencyKey) {
        return store.admittedPayload(workspaceId, idempotencyKey);
    }

    /** The actions of a workspace under these keys, by key. */
    @Transactional(readOnly = true)
    public Map<String, LaneAction> byKeys(long workspaceId, Collection<String> idempotencyKeys) {
        return store.byKeys(workspaceId, idempotencyKeys);
    }

    /**
     * Whether an attempt still holds its action, for a domain write that the attempt makes in its own transaction. A
     * write that names a lease that moved on must not change anything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holds(ActionAttempt attempt) {
        return store.holds(attempt.id(), attempt.epoch());
    }

    /** The newest action of a workspace's lane, read under the lock of the lane's domain row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LaneAction> newestInLane(long workspaceId, String laneKey) {
        return store.newestInLane(workspaceId, laneKey);
    }

    /** Cancels the lane's actions that have not started, in the caller's transaction. Their adapter is not told. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPending(long workspaceId, String laneKey) {
        return store.cancelPending(workspaceId, laneKey);
    }

    /** The most attempts of a live action of these kinds; 0 when none is live. */
    @Transactional(readOnly = true)
    public int maxLiveAttempts(Collection<ActionKind> kinds) {
        return store.maxLiveAttempts(kinds);
    }
}
