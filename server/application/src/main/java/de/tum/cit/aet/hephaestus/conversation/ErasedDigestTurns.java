package de.tum.cit.aet.hephaestus.conversation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stops digest turns whose stimulus was erased. The caller cancels their runs in the same transaction. */
@Component
@RequiredArgsConstructor
public class ErasedDigestTurns {
    private final ConversationTurnRepository turns;
    private final ApplicationEventPublisher events;

    public record StoppedRun(long workspaceId, UUID runId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StoppedRun> stopQueued(Collection<Long> workspaceIds, @Nullable UUID conversationId, int limit) {
        if (workspaceIds.isEmpty()) return List.of();
        var erased = turns.lockErasedQueuedDigests(workspaceIds, conversationId, limit);
        if (erased.isEmpty()) return List.of();
        Instant now = turns.databaseClock();
        return erased.stream()
                .map(row -> turns.lockByIdAndWorkspaceId(row.getId(), row.getWorkspaceId())
                        .orElseThrow())
                .map(turn -> {
                    stop(turn, now);
                    return new StoppedRun(turn.getWorkspaceId(), turn.getRunId());
                })
                .toList();
    }

    void stop(ConversationTurn turn, Instant now) {
        if (turn.stopIfActive(now)) events.publishEvent(ConversationLedgerEvents.TurnChanged.of(turn));
    }
}
