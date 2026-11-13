package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The outbox's short transactions: each one statement of {@link AgentActionRepository}, mapped to records. */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("The outbox runs the actions of every workspace and of the instance; each adapter scopes its work")
class ActionStore {

    /** What {@link #claim} hands to the executor. */
    record Claim(
            UUID id,
            @Nullable Long workspaceId,
            ActionKind kind,
            String idempotencyKey,
            String payload,
            int attempts,
            long epoch,
            boolean writeStarted,
            @Nullable String readbackRef,
            @Nullable String nativeRecipient) {}

    /** The state an action moved to, with its reason once it ended. */
    record Settled(
            UUID id,
            @Nullable Long workspaceId,
            ActionKind kind,
            String idempotencyKey,
            ActionState state,
            @Nullable ActionStopReason reason,
            @Nullable String readbackRef) {

        /** The action as its adapter learns it, when it ended. */
        Optional<ActionEnded> ended() {
            ActionStopReason stop = reason;
            return stop == null
                    ? Optional.empty()
                    : Optional.of(new ActionEnded(id, workspaceId, kind, idempotencyKey, state, stop, readbackRef));
        }
    }

    private static final List<String> AT_MOST_ONCE = Arrays.stream(ActionKind.values())
            .filter(ActionKind::atMostOnce)
            .map(ActionKind::name)
            .toList();

    private final AgentActionRepository actions;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public int eraseRecipient(long workspaceId, PersonIdentity recipient) {
        return actions.eraseRecipient(workspaceId, mapper.writeValueAsString(recipient));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int eraseKinds(long workspaceId, Collection<ActionKind> kinds) {
        return kinds.isEmpty()
                ? 0
                : actions.eraseKinds(
                        workspaceId, kinds.stream().map(ActionKind::name).toList());
    }

    /** Adds the action in the caller's transaction, or returns the one already enqueued under its key. */
    @Transactional
    public UUID enqueue(ActionRequest request) {
        return add(request, Duration.ZERO)
                .or(() -> actions.findIdByIdempotencyKey(request.idempotencyKey()))
                .orElseThrow(() -> new IllegalStateException("An action under this key is being enqueued"));
    }

    /** Adds the action, due after {@code delay}; empty when its key was already enqueued. */
    @Transactional
    public Optional<UUID> insert(ActionRequest request, Duration delay) {
        return add(request, delay);
    }

    private Optional<UUID> add(ActionRequest request, Duration delay) {
        return actions.insertAdmitted(
                TimeOrderedIds.next(),
                request.workspaceId(),
                request.kind().name(),
                request.laneKey(),
                request.idempotencyKey(),
                mapper.writeValueAsString(request.payload()),
                request.subjectAccountId(),
                request.nativeRecipient() == null ? null : mapper.writeValueAsString(request.nativeRecipient()),
                request.maxAttempts(),
                request.expiresAt(),
                delay.toSeconds());
    }

    /** Takes one admitted lane head now ({@link AgentActionRepository#claimById}). */
    @Transactional
    public Optional<Claim> claim(UUID id, Duration lease, ActionAdmission admission) {
        return actions.claimById(id, lease.toSeconds(), admission.json()).map(ActionStore::claim);
    }

    @Transactional(readOnly = true)
    public Map<String, LaneAction> byKeys(long workspaceId, Collection<String> keys) {
        if (keys.isEmpty()) return Map.of();
        var found = new HashMap<String, LaneAction>();
        for (var row : actions.findByKeys(workspaceId, keys)) found.put(row.getIdempotencyKey(), lane(row));
        return found;
    }

    /**
     * Adds the action already claimed by the caller, for an effect whose answer the caller waits for. Empty when the
     * key exists: that effect belongs to its first caller.
     */
    @Transactional
    public Optional<Claim> start(ActionRequest request, Duration lease) {
        return actions.insertExecuting(
                        TimeOrderedIds.next(),
                        request.workspaceId(),
                        request.kind().name(),
                        request.laneKey(),
                        request.idempotencyKey(),
                        mapper.writeValueAsString(request.payload()),
                        request.subjectAccountId(),
                        request.nativeRecipient() == null ? null : mapper.writeValueAsString(request.nativeRecipient()),
                        request.maxAttempts(),
                        request.expiresAt(),
                        lease.toSeconds())
                .map(ActionStore::claim);
    }

    /** Takes up to {@code limit} due lane heads under a new lease ({@link AgentActionRepository#claim}). */
    @Transactional
    public List<Claim> claim(int limit, Duration lease, ActionAdmission admission) {
        return actions.claim(limit, lease.toSeconds(), AT_MOST_ONCE, admission.json()).stream()
                .map(ActionStore::claim)
                .toList();
    }

    /** Commits on its own, also inside a caller's transaction: the fence must be durable before the provider call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginWrite(UUID id, long epoch, Duration margin) {
        return actions.beginWrite(id, epoch, margin.toSeconds()) == 1;
    }

    /**
     * Records the attempt's result; empty when another claim took the action or an erasure deleted it. When the action
     * ends, {@code onEnd} runs in this transaction, so the domain row ends with it.
     */
    @Transactional
    public Optional<Settled> settle(UUID id, long epoch, ActionResult result, Consumer<ActionEnded> onEnd) {
        Optional<AgentActionRepository.SettledRow> row = switch (result) {
            case ActionResult.Done done ->
                actions.end(id, epoch, "DONE", ActionStopReason.COMPLETED.name(), done.readbackRef());
            case ActionResult.Retry retry ->
                actions.retry(id, epoch, retry.after().toSeconds());
            case ActionResult.Defer defer ->
                actions.defer(id, epoch, defer.after().toSeconds());
            case ActionResult.Reread reread ->
                actions.reread(id, epoch, reread.after().toSeconds());
            case ActionResult.Uncertain uncertain ->
                actions.end(id, epoch, "UNCERTAIN", uncertain.reason().name(), null);
            case ActionResult.Failed failed ->
                actions.end(id, epoch, "FAILED", failed.reason().name(), null);
            case ActionResult.Cancelled cancelled ->
                actions.end(id, epoch, "CANCELLED", cancelled.reason().name(), null);
            case ActionResult.Abandoned abandoned -> Optional.empty();
        };
        Optional<Settled> settled = row.map(ActionStore::settled);
        settled.flatMap(Settled::ended).ifPresent(onEnd);
        return settled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holds(UUID id, long epoch) {
        return actions.holds(id, epoch);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LaneAction> newestInLane(long workspaceId, String laneKey) {
        return actions.newestInLane(workspaceId, laneKey).map(ActionStore::lane);
    }

    private static LaneAction lane(AgentActionRepository.LaneRow row) {
        String reason = row.getStopReason();
        return new LaneAction(
                row.getId(),
                ActionKind.valueOf(row.getKind()),
                row.getIdempotencyKey(),
                ActionState.valueOf(row.getState()),
                row.getAttempts(),
                row.getRereading(),
                row.getNextAttemptAt(),
                reason == null ? null : ActionStopReason.valueOf(reason),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPending(long workspaceId, String laneKey) {
        return actions.cancelPending(workspaceId, laneKey);
    }

    @Transactional(readOnly = true)
    public int maxLiveAttempts(Collection<ActionKind> kinds) {
        return actions.maxLiveAttempts(kinds.stream().map(ActionKind::name).toList());
    }

    /** Up to {@code limit} actions that {@link #endStale} can end. */
    @Transactional(readOnly = true)
    public List<UUID> findStale(int limit, ActionAdmission admission) {
        return actions.findStale(AT_MOST_ONCE, admission.json(), limit);
    }

    /**
     * Ends one action that can no longer run: it expired before it started, or lost its lease for good. {@code onEnd}
     * runs in this transaction, so a failing domain row holds back only its own action.
     */
    @Transactional
    public Optional<Settled> endStale(UUID id, ActionAdmission admission, Consumer<ActionEnded> onEnd) {
        Optional<Settled> settled = actions.endExpired(id, admission.json())
                .or(() -> actions.endLost(id, AT_MOST_ONCE, admission.json()))
                .map(ActionStore::settled);
        settled.flatMap(Settled::ended).ifPresent(onEnd);
        return settled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetEnded(long workspaceId, Collection<String> keys) {
        if (keys.isEmpty()) return;
        // Redaction may commit while a terminal action is still uncommitted. Read its state after that end commits.
        actions.lockByKeys(workspaceId, keys);
        actions.deleteEndedByKeys(workspaceId, keys);
    }

    /** Deletes what ended more than {@code retention} ago. */
    @Transactional
    public int deleteEnded(Duration retention) {
        return actions.deleteEndedBefore((int) retention.toDays(), ActionKind.names());
    }

    private static Settled settled(AgentActionRepository.SettledRow row) {
        String reason = row.getStopReason();
        return new Settled(
                row.getId(),
                row.getWorkspaceId(),
                ActionKind.valueOf(row.getKind()),
                row.getIdempotencyKey(),
                ActionState.valueOf(row.getState()),
                reason == null ? null : ActionStopReason.valueOf(reason),
                row.getReadbackRef());
    }

    private static Claim claim(AgentActionRepository.ClaimRow row) {
        return new Claim(
                row.getId(),
                row.getWorkspaceId(),
                ActionKind.valueOf(row.getKind()),
                row.getIdempotencyKey(),
                row.getPayload(),
                row.getAttempts(),
                row.getEpoch(),
                row.getWriteStarted(),
                row.getReadbackRef(),
                row.getNativeRecipient());
    }
}
