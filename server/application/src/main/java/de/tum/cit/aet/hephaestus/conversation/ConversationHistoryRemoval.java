package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Ends the attempts that could have read removed history, while the removal holds the history lock. */
@Component
@RequiredArgsConstructor
class ConversationHistoryRemoval {

    private final ConversationTurnRepository rows;
    private final ConversationTurnService turns;
    private final TurnRuns runs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @EventListener
    public void contextChanged(ConversationLedgerEvents.ContextChanged change) {
        Long accountId = change.accountId();
        if (accountId == null) return;
        for (ConversationTurn turn : rows.lockHeldOfAccount(change.workspaceId(), accountId)) {
            var taken = turns.takeBack(
                    turn,
                    false,
                    clock.instant(),
                    "Part of the conversation history was removed while Hephaestus was replying. Ask again to get an answer.",
                    ConversationTurn.CONTEXT_CHANGED_CAUSE);
            runs.ended(change.workspaceId(), turn.getRunId(), taken.attempt(), turn.getStatus());
            events.publishEvent(new ConversationLedgerEvents.AttemptTakenBack(
                    change.workspaceId(),
                    turn.getId(),
                    taken.attempt(),
                    taken.admission(),
                    taken.startedAt(),
                    taken.lostAt()));
        }
    }
}
