package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The admitted answer and its durable notification actions commit together. No transport runs in this transaction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DigestDelivery {
    private final ConversationTurnAccess turns;
    private final ConversationAccess conversations;
    private final DigestAdmission admission;
    private final List<WeeklyDigestTransport> transports;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ConversationLedgerEvents.TurnChanged changed) {
        if (changed.status() != ConversationTurn.Status.DONE || changed.surface() != ConversationTurn.Surface.SYSTEM)
            return;
        var turn = turns.find(changed.workspaceId(), changed.turnId())
                .filter(row -> row.status() == ConversationTurn.Status.DONE
                        && row.surface() == ConversationTurn.Surface.SYSTEM)
                .orElse(null);
        if (turn == null) return;
        long accountId = conversations.accountId(changed.workspaceId(), turn.conversationId());
        if (admission.refuses(changed.workspaceId(), changed.turnId())) return;
        var stimulus = conversations.stimulus(changed.workspaceId(), changed.turnId());
        if (stimulus.kind() != ConversationEntry.Kind.DIGEST
                || stimulus.redacted()
                || !conversations.answered(changed.workspaceId(), changed.turnId())) return;
        var payload = WeeklyDigestPayload.fromJson(stimulus.payload(), mapper);
        if (payload.isEmpty()) return;
        events.publishEvent(new ConversationLedgerEvents.EntryChanged(
                changed.workspaceId(), changed.conversationId(), List.of(stimulus.id())));
        var pointer = transports.stream()
                .sorted(Comparator.comparing(WeeklyDigestTransport::pointer))
                .filter(transport -> transport.reaches(changed.workspaceId(), accountId))
                .map(WeeklyDigestTransport::pointer)
                .findFirst()
                .orElse(WeeklyDigestPointer.NONE);
        events.publishEvent(new WeeklyDigestWritten(
                stimulus.id(),
                changed.workspaceId(),
                accountId,
                payload.get().window().days(),
                pointer,
                clock.instant()));
    }
}
