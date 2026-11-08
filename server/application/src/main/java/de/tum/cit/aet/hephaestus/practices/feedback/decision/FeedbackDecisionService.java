package de.tum.cit.aet.hephaestus.practices.feedback.decision;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackChecks;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionReason;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackDecisionService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackOutcomes outcomes;
    private final FeedbackDecisionRepository decisions;
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final FeedbackGate gate;
    private final FeedbackContentDigest digests;
    private final PersonDataWriteFence privacy;
    private final FeedbackSendQueue sendQueue;
    private final FeedbackPublicationContext publication;

    /**
     * Records a decision. A refused approval whose cause is the publication context returns, rather than throws, so
     * its committed check, and for a changed context its withholding, stay with the feedback.
     */
    @Transactional
    public FeedbackDecisionOutcome decide(
            long workspaceId, UUID feedbackId, long actorAccountId, FeedbackDecisionInput request) {
        var action = request.action();
        var reason = request.reason();
        var note = request.note();
        var contentDigest = request.contentDigest();
        if (actorAccountId <= 0) throw new IllegalArgumentException("A decision requires an authenticated account.");
        validate(action, reason, note, contentDigest);
        if (!privacy.holdForAccountWrite(actorAccountId)) {
            throw conflict("Your account cannot record feedback decisions.");
        }
        Feedback feedback = feedbackRepository
                .lockByIdAndWorkspaceId(feedbackId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
        if (feedback.getDestination() == FeedbackDestination.ON_WORK && feedback.getState() == FeedbackState.REPLACED)
            throw conflict("A newer revision owns this copy. Open that revision to change its delivery.");
        var send = sendQueue.lock(workspaceId, feedbackId);
        var lockedCopies = copies.lockByFeedbackId(workspaceId, feedbackId);
        Instant now = clock.instant();
        if (action == FeedbackDecisionAction.RESTORE
                && feedback.getDestination() != FeedbackDestination.ON_WORK
                && !gate.evaluate(feedback, FeedbackCheckStage.EGRESS, false).allowed()) {
            throw conflict("Current delivery policy does not permit restoration.");
        }
        if (action == FeedbackDecisionAction.APPROVE || action == FeedbackDecisionAction.REJECT) {
            if (copies.hasWriteFence(workspaceId, feedbackId)
                    || send.filter(row -> row.getState() == FeedbackSendState.SENDING)
                            .isPresent()) {
                throw conflict("A provider write has started. You cannot change its approval.");
            }
            String currentDigest = digests.current(workspaceId, feedbackId);
            if (!Objects.equals(currentDigest, contentDigest)) {
                throw conflict("The feedback changed. Refresh it before deciding.");
            }
            FeedbackState currentState = feedback.getState();
            boolean allowed = action == FeedbackDecisionAction.APPROVE
                    ? currentState == FeedbackState.AWAITING_APPROVAL || currentState == FeedbackState.REJECTED
                    : currentState == FeedbackState.AWAITING_APPROVAL || currentState == FeedbackState.READY;
            if (!allowed) {
                throw conflict("This feedback is no longer awaiting that decision.");
            }
            if (action == FeedbackDecisionAction.APPROVE) {
                if (basis.isIncorrect(workspaceId, feedbackId))
                    throw conflict("The results of this feedback are marked incorrect.");
                var check = gate.evaluate(feedback, FeedbackCheckStage.APPROVED, false);
                if (!check.allowed()) throw conflict("Current delivery policy does not permit approval.");
                var refusal = publication.refusal(feedback).orElse(null);
                if (refusal != null)
                    check = new FeedbackCheck(check.stage(), false, refusal, check.checkedAt(), check.facts());
                var checks = new EnumMap<FeedbackCheckStage, FeedbackChecks>(FeedbackCheckStage.class);
                checks.putAll(feedback.getChecks());
                checks.put(check.stage(), new FeedbackChecks(List.of(check), check.checkedAt()));
                feedbackRepository.updateChecks(workspaceId, feedbackId, checks);
                if (refusal != null) return refused(feedback, refusal);
            }
            outcomes.update(
                    feedback,
                    action == FeedbackDecisionAction.APPROVE ? FeedbackState.READY : FeedbackState.REJECTED,
                    null,
                    feedback.getDeliveredAt());
            if (feedback.getDestination() == FeedbackDestination.ON_WORK) {
                sendQueue.queue(
                        feedback,
                        FeedbackSendAction.POST,
                        action == FeedbackDecisionAction.APPROVE ? FeedbackSendState.PENDING : FeedbackSendState.DONE);
            }
        }
        FeedbackDecision decision = decisions.save(FeedbackDecision.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .feedbackId(feedbackId)
                .action(action)
                .actorAccountId(actorAccountId)
                .reason(reason == null ? null : reason.name())
                .note(note)
                .contentDigest(contentDigest)
                .decidedAt(now)
                .build());
        if ((action == FeedbackDecisionAction.WITHDRAW || action == FeedbackDecisionAction.RESTORE)
                && feedback.getDestination() == FeedbackDestination.ON_WORK
                && send.map(row -> row.getState() == FeedbackSendState.DONE).orElse(true)) {
            if (lockedCopies.stream()
                    .anyMatch(copy -> copy.getState() == FeedbackCopyState.POSTED
                            || copy.getState() == FeedbackCopyState.RETRACTED)) {
                sendQueue.queue(feedback, FeedbackSendAction.RETRACT, FeedbackSendState.PENDING);
            } else if (action == FeedbackDecisionAction.RESTORE
                    && feedback.getState() == FeedbackState.READY
                    && !lockedCopies.isEmpty()
                    && lockedCopies.stream()
                            .allMatch(copy -> copy.getState() == FeedbackCopyState.PLANNED
                                    && copy.getWriteStartedAt() == null
                                    && copy.getPostedAt() == null
                                    && copy.getExternalRef() == null)) {
                sendQueue.queue(feedback, FeedbackSendAction.POST, FeedbackSendState.PENDING);
            }
        }
        if (action == FeedbackDecisionAction.APPROVE) {
            events.publishEvent(new ApprovedFeedbackReadyEvent(workspaceId, feedbackId));
        }
        return new FeedbackDecisionOutcome(
                decision.getId(),
                null,
                action == FeedbackDecisionAction.APPROVE
                        ? FeedbackState.READY
                        : action == FeedbackDecisionAction.REJECT ? FeedbackState.REJECTED : feedback.getState());
    }

    /**
     * A changed context is not accepted again for these words, so the proposal is withheld; an unresolved write
     * of another review can still be read back, so the proposal keeps awaiting approval.
     */
    private FeedbackDecisionOutcome refused(Feedback feedback, FeedbackStopReason refusal) {
        if (refusal != FeedbackStopReason.PUBLIC_HISTORY_CHANGED)
            return new FeedbackDecisionOutcome(null, refusal, feedback.getState());
        copies.withholdPlanned(feedback.getWorkspaceId(), feedback.getId(), refusal.name());
        outcomes.update(feedback, FeedbackState.WITHHELD, refusal, feedback.getDeliveredAt());
        return new FeedbackDecisionOutcome(null, refusal, FeedbackState.WITHHELD);
    }

    private static void validate(
            FeedbackDecisionAction action,
            @Nullable FeedbackDecisionReason reason,
            @Nullable String note,
            @Nullable String digest) {
        if (note != null && note.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep your note within 500 characters.");
        }
        if (digest != null && !digest.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The content digest must contain 64 hexadecimal characters.");
        }
        if ((action == FeedbackDecisionAction.APPROVE || action == FeedbackDecisionAction.REJECT) && digest == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval needs the current content digest.");
        }
        if (action == FeedbackDecisionAction.APPROVE && reason != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval cannot include a rejection reason.");
        }
        if ((action == FeedbackDecisionAction.REJECT || action == FeedbackDecisionAction.WITHDRAW)
                && reason == null
                && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add a reason or a note for this decision.");
        }
        if (action == FeedbackDecisionAction.RESTORE && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add a note to explain the restoration.");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
