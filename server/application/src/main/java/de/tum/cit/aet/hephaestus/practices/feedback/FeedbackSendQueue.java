package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class FeedbackSendQueue {
    private final FeedbackSendRepository sends;
    private final Clock clock;
    private final FeedbackCopyRepository copies;
    private final FeedbackVisibilityService visibility;
    private final ConfigAuditPort audit;

    /** Locks the send row of a feedback, if it has one. */
    public Optional<FeedbackSend> lock(long workspaceId, UUID feedbackId) {
        return sends.lockByFeedbackId(workspaceId, feedbackId);
    }

    @Transactional
    public void queue(Feedback row, FeedbackSendAction action, FeedbackSendState state) {
        enqueue(
                row,
                action == FeedbackSendAction.POST && row.getComposition().hasNonNull("editOf")
                        ? FeedbackSendAction.EDIT
                        : action,
                state);
    }

    private void enqueue(Feedback row, FeedbackSendAction action, FeedbackSendState state) {
        var prior = sends.lockByFeedbackId(row.getWorkspaceId(), row.getId());
        if (prior.filter(send -> send.getState() == FeedbackSendState.SENDING
                        || (action == FeedbackSendAction.RETRACT
                                && send.getAction() == FeedbackSendAction.POST
                                && send.getState() != FeedbackSendState.DONE))
                .isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The existing send must settle first.");
        Instant now = clock.instant();
        sends.saveAndFlush(FeedbackSend.builder()
                .feedbackId(row.getId())
                .workspaceId(row.getWorkspaceId())
                .action(action)
                .state(state)
                .attempts(prior.map(FeedbackSend::getAttempts).orElse(0))
                .nextAttemptAt(now)
                .createdAt(prior.map(FeedbackSend::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build());
    }

    /**
     * Queues the existing readback of a write whose visibility stayed unknown after its send settled. The claimed
     * attempt still holds the started copy, so the send only reads back and never creates a provider copy; planned
     * siblings are withheld as unconfirmed. The send keeps its attempt history and so its bounded readback cadence.
     * A withdrawal still needs that readback: a found copy can then be retracted, while absence releases its fence.
     * A settled retraction counts too: it corrects only posted copies and never reads a started one back.
     * The caller holds the feedback row lock. The configuration audit trail records who asked for the readback,
     * and when, in that same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recheck(Feedback row) {
        long workspaceId = row.getWorkspaceId();
        UUID feedbackId = row.getId();
        var send = sends.lockByFeedbackId(workspaceId, feedbackId).filter(s -> s.getState() == FeedbackSendState.DONE);
        int unknown = (int) copies.lockByFeedbackId(workspaceId, feedbackId).stream()
                .filter(c -> c.getState() == FeedbackCopyState.WRITE_STARTED
                        && c.getWriteStartedAt() != null
                        && c.getPostedAt() == null
                        && c.getExternalRef() == null
                        && c.getReadback() != FeedbackCopyReadback.ABSENT)
                .count();
        if (row.getDestination() != FeedbackDestination.ON_WORK || send.isEmpty() || unknown == 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No copy of this feedback has unknown visibility to recheck.");
        boolean withdrawn = visibility.withdrawn(workspaceId, feedbackId);
        var prior = send.get().getAction();
        int attempts = send.get().getAttempts();
        enqueue(row, FeedbackSendAction.POST, FeedbackSendState.PENDING);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.FEEDBACK_DELIVERY_RECHECK,
                feedbackId,
                workspaceId,
                new FeedbackDeliveryRecheckSnapshot(prior, FeedbackSendState.DONE, attempts, unknown, withdrawn),
                new FeedbackDeliveryRecheckSnapshot(
                        FeedbackSendAction.POST, FeedbackSendState.PENDING, attempts, unknown, withdrawn)));
    }
}
