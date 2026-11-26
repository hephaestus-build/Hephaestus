package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fences the live copies that can read an owner's conversation tree, including sibling threads through search. */
@Component
@RequiredArgsConstructor
public class ConversationHistoryRemoval {

    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final TurnRuns runs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public static String lockKey(long workspaceId, long accountId) {
        return "conversation-history:" + workspaceId + ":" + accountId;
    }

    /**
     * Locks out claims and gateway writes, then cancels turns before locking the ledger.
     * The caller redacts the entries and the scope listener revokes sandboxes in this same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean fence(long workspaceId, long accountId, List<UUID> entryIds) {
        conversations.lockScope(lockKey(workspaceId, accountId));
        if (!entries.hasHistoryToRemove(workspaceId, accountId, entryIds.toArray(UUID[]::new))) return false;

        // The sandbox listener revokes before commit. The ledger delivers this change only on commit.
        events.publishEvent(new ConversationLedgerEvents.ScopeChanged(workspaceId, accountId));
        Instant now = clock.instant();
        List<ConversationTurn> affected = turns.lockForHistoryRemoval(workspaceId, accountId, entryIds);
        affected.forEach(turn -> turn.stopIfActive(now));
        turns.flush();
        for (ConversationTurn turn : affected) {
            runs.cancel(workspaceId, turn.getRunId());
            events.publishEvent(ConversationLedgerEvents.TurnChanged.of(turn));
            Instant startedAt = turn.getStartedAt();
            if (startedAt != null) {
                events.publishEvent(new ConversationLedgerEvents.AttemptTakenBack(
                        workspaceId, turn.getId(), turn.getAttempt(), turn.getAdmission(), startedAt, now));
            }
        }
        conversations.advanceScopeEpoch(workspaceId, accountId);
        events.publishEvent(new ConversationTurnWaits.SlotsChanged(workspaceId));
        return true;
    }
}
