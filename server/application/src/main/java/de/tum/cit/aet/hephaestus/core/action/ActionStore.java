package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
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
            @Nullable String readbackRef) {}

    /** The state an action moved to, with its reason once it ended. */
    record Settled(
            UUID id,
            @Nullable Long workspaceId,
            ActionKind kind,
            ActionState state,
            @Nullable ActionStopReason reason) {}

    private static final List<String> AT_MOST_ONCE = Arrays.stream(ActionKind.values())
            .filter(ActionKind::atMostOnce)
            .map(ActionKind::name)
            .toList();

    private final AgentActionRepository actions;
    private final ObjectMapper mapper;

    /** Adds the action in the caller's transaction, or returns the one already enqueued under its key. */
    @Transactional
    public UUID enqueue(ActionRequest request) {
        return actions.insertAdmitted(
                        TimeOrderedIds.next(),
                        request.workspaceId(),
                        request.kind().name(),
                        request.laneKey(),
                        request.idempotencyKey(),
                        mapper.writeValueAsString(request.payload()),
                        request.subjectAccountId(),
                        request.maxAttempts(),
                        request.expiresAt())
                .or(() -> actions.findIdByIdempotencyKey(request.idempotencyKey()))
                .orElseThrow(() -> new IllegalStateException("An action under this key is being enqueued"));
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
                        request.maxAttempts(),
                        request.expiresAt(),
                        lease.toSeconds())
                .map(ActionStore::claim);
    }

    /** Takes up to {@code limit} due lane heads under a new lease ({@link AgentActionRepository#claim}). */
    @Transactional
    public List<Claim> claim(int limit, Duration lease) {
        return actions.claim(limit, lease.toSeconds(), AT_MOST_ONCE).stream()
                .map(ActionStore::claim)
                .toList();
    }

    @Transactional
    public boolean beginWrite(UUID id, long epoch, Duration margin) {
        return actions.beginWrite(id, epoch, margin.toSeconds()) == 1;
    }

    /** Records the attempt's result; empty when another claim took the action or an erasure deleted it. */
    @Transactional
    public Optional<Settled> settle(UUID id, long epoch, ActionResult result) {
        Optional<AgentActionRepository.SettledRow> row = switch (result) {
            case ActionResult.Done done ->
                actions.end(id, epoch, "DONE", ActionStopReason.COMPLETED.name(), done.readbackRef());
            case ActionResult.Retry retry ->
                actions.retry(id, epoch, retry.after().toSeconds());
            case ActionResult.Defer defer ->
                actions.defer(id, epoch, defer.after().toSeconds());
            case ActionResult.Uncertain uncertain ->
                actions.end(id, epoch, "UNCERTAIN", uncertain.reason().name(), null);
            case ActionResult.Failed failed ->
                actions.end(id, epoch, "FAILED", failed.reason().name(), null);
            case ActionResult.Cancelled cancelled ->
                actions.end(id, epoch, "CANCELLED", cancelled.reason().name(), null);
            case ActionResult.Abandoned abandoned -> Optional.empty();
        };
        return row.map(ActionStore::settled);
    }

    /** Ends what can no longer run: what expired before it started, and what lost its lease for good. */
    @Transactional
    public List<Settled> endStale() {
        var ended = new ArrayList<Settled>();
        actions.endExpired().forEach(row -> ended.add(settled(row)));
        actions.endLost(AT_MOST_ONCE).forEach(row -> ended.add(settled(row)));
        return ended;
    }

    /** Deletes what ended more than {@code retention} ago. */
    @Transactional
    public int deleteEnded(Duration retention) {
        return actions.deleteEndedBefore((int) retention.toDays());
    }

    private static Settled settled(AgentActionRepository.SettledRow row) {
        String reason = row.getStopReason();
        return new Settled(
                row.getId(),
                row.getWorkspaceId(),
                ActionKind.valueOf(row.getKind()),
                ActionState.valueOf(row.getState()),
                reason == null ? null : ActionStopReason.valueOf(reason));
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
                row.getReadbackRef());
    }
}
