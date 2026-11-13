package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
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
    private final MentorTurnRepository turns;
    private final MentorTurnService turnService;
    private final MentorEntryRepository entries;
    private final DigestAdmission admission;
    private final List<WeeklyDigestTransport> transports;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(MentorLedgerEvents.TurnChanged changed) {
        if (changed.status() != MentorTurn.Status.DONE || changed.surface() != MentorTurn.Surface.SYSTEM) return;
        var turn = turns.findByIdAndWorkspaceId(changed.turnId(), changed.workspaceId())
                .filter(row ->
                        row.getStatus() == MentorTurn.Status.DONE && row.getSurface() == MentorTurn.Surface.SYSTEM)
                .orElse(null);
        if (turn == null) return;
        long accountId = turnService.accountId(turn);
        if (admission.refuses(changed.workspaceId(), changed.turnId())) return;
        var stimulus = entries.findStimulus(changed.workspaceId(), changed.turnId());
        if (stimulus.getKind() != MentorEntry.Kind.DIGEST
                || stimulus.isRedacted()
                || entries
                        .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                                changed.workspaceId(), changed.turnId(), MentorEntry.Kind.HEPH)
                        .stream()
                        .noneMatch(entry -> !entry.isRedacted())) return;
        var payload = WeeklyDigestPayload.fromJson(stimulus.getPayload(), mapper);
        if (payload.isEmpty()) return;
        events.publishEvent(new MentorLedgerEvents.EntryChanged(
                changed.workspaceId(), changed.conversationId(), List.of(stimulus.getId())));
        var pointer = transports.stream()
                .sorted(Comparator.comparing(WeeklyDigestTransport::pointer))
                .filter(transport -> transport.reaches(changed.workspaceId(), accountId))
                .map(WeeklyDigestTransport::pointer)
                .findFirst()
                .orElse(WeeklyDigestPointer.NONE);
        events.publishEvent(new WeeklyDigestWritten(
                stimulus.getId(),
                changed.workspaceId(),
                accountId,
                payload.get().window().days(),
                pointer,
                clock.instant()));
    }
}
