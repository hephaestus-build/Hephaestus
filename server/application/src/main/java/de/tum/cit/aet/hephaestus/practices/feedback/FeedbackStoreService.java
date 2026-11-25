package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackCompositionContextService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnServerRole
public class FeedbackStoreService implements FeedbackStore {
    private @Nullable EvaluationObservations evaluation;

    @Autowired(required = false)
    public void setEvaluationObservations(EvaluationObservations observer) {
        evaluation = observer;
    }

    private final FeedbackRepository feedback;
    private final FeedbackOutcomes outcomes;
    private final FeedbackCopyRepository copies;
    private final FeedbackDecisionService decisions;
    private final FeedbackResponseService responses;
    private final FeedbackVisibilityService visibility;
    private final FeedbackCompositionContextService composition;
    private final FeedbackSendQueue sendQueue;
    private final FeedbackDeliveryPolicy policy;
    private final FeedbackPreparationStore prepared;

    @Override
    @Transactional(readOnly = true)
    public FeedbackCompositionContext compositionContext(
            long workspaceId, UUID reviewId, List<UUID> admittedResultIds) {
        return composition.read(workspaceId, reviewId, admittedResultIds);
    }

    @Override
    @Transactional(readOnly = true)
    public PrivateFeedbackProgress privateProgress(
            long workspaceId, UUID reviewId, long developerId, Collection<UUID> admittedResultIds) {
        return prepared.privateProgress(workspaceId, reviewId, developerId, admittedResultIds);
    }

    @Override
    public PreparedPackage prepareAll(long workspaceId, UUID reviewId, List<FeedbackDraft> drafts) {
        return prepared.prepareAll(workspaceId, reviewId, drafts);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkPackage(long workspaceId, UUID reviewId, List<FeedbackDraft> drafts) {
        prepared.checkPackage(workspaceId, reviewId, drafts);
    }

    @Override
    public FeedbackDecisionOutcome decide(
            long workspaceId, UUID feedbackId, long actorId, FeedbackDecisionInput input) {
        return decisions.decide(workspaceId, feedbackId, actorId, input);
    }

    @Override
    public void respond(
            long workspaceId,
            UUID feedbackId,
            long developerId,
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment) {
        responses.respond(workspaceId, feedbackId, developerId, resolution, usefulness, comment);
    }

    @Override
    public void retry(long workspaceId, UUID feedbackId, long actorId) {
        if (actorId <= 0) throw new IllegalArgumentException("A retry requires an authenticated account.");
        var row = lock(workspaceId, feedbackId);
        var send = sendQueue.current(workspaceId, feedbackId);
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        if (row.getDestination() == FeedbackDestination.ON_WORK && visibility.withdrawn(workspaceId, feedbackId)) {
            retryWithdrawal(row, send, children);
            return;
        }
        if (row.getDestination() != FeedbackDestination.ON_WORK
                || (row.getState() != FeedbackState.FAILED && row.getState() != FeedbackState.DELIVERED)
                || send.filter(s -> s.getState() != FeedbackSendState.DONE).isPresent()
                || children.stream()
                        .anyMatch(c -> c.getState() == FeedbackCopyState.WRITE_STARTED
                                || (c.getState() == FeedbackCopyState.FAILED
                                        && c.getWriteStartedAt() != null
                                        && c.getReadback() != FeedbackCopyReadback.ABSENT)))
            throw conflict("This feedback cannot be retried safely.");
        var check = check(row, true);
        if (!check.allowed()) throw conflict("Current delivery policy does not permit this retry.");
        if (copies.retryAbsent(workspaceId, feedbackId) == 0) throw conflict("No absent copy can be retried.");
        boolean exposed = row.getDeliveredAt() != null;
        outcomes.update(
                row,
                exposed ? FeedbackState.UNCONFIRMED : FeedbackState.READY,
                exposed ? FeedbackStopReason.DELIVERY_UNCONFIRMED : null,
                row.getDeliveredAt());
        sendQueue.queue(row, FeedbackSendAction.POST, FeedbackSendState.PENDING);
    }

    public void recheck(long workspaceId, UUID feedbackId, long actorId) {
        if (actorId <= 0) throw new IllegalArgumentException("A recheck requires an authenticated account.");
        sendQueue.recheck(lock(workspaceId, feedbackId));
    }

    /**
     * Runs the recorded withdrawal's correction again after it stopped with a posted copy whose removal the provider
     * did not acknowledge. It records no decision and keeps the copies' receipts. The copies decide: the withdrawal's own
     * send may be gone, because the outbox keeps an ended send for 30 days.
     */
    private void retryWithdrawal(Feedback row, Optional<FeedbackSend> send, List<FeedbackCopy> children) {
        if (send.filter(s -> s.getState() != FeedbackSendState.DONE).isPresent())
            throw conflict("The withdrawal is still in progress.");
        if (children.stream()
                .noneMatch(c -> c.getState() == FeedbackCopyState.POSTED
                        && c.getExternalRef() != null
                        && c.getReadback() == FeedbackCopyReadback.TRANSIENT))
            throw conflict("No withdrawn copy can be retried.");
        sendQueue.queue(row, FeedbackSendAction.RETRACT, FeedbackSendState.PENDING);
    }

    public FeedbackCheck check(Feedback row, boolean approval) {
        return policy.check(row, FeedbackCheckStage.EGRESS, approval);
    }

    /** Records the developer's first read of ready practice-page feedback, which delivers it. */
    public boolean recordFirstRead(Feedback row, long developerId, Instant readAt) {
        return outcomes.firstRead(row, developerId, readAt);
    }

    /** {@link #check} for the pieces of one workspace, keyed by feedback id. */
    public Map<UUID, FeedbackCheck> checkAll(long workspaceId, List<Feedback> rows, boolean approval) {
        return policy.checkAll(workspaceId, rows, FeedbackCheckStage.EGRESS, approval);
    }

    public Feedback lock(long workspaceId, UUID id) {
        return feedback.lockByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", id.toString()));
    }

    public void withhold(Feedback row, FeedbackStopReason reason) {
        if (evaluation != null)
            evaluation.record(new EvaluationObservations.Silence(row.getWorkspaceId(), row.getId(), reason));
        policy.withhold(row, reason);
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
