package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackCompositionContextService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackStoreService implements FeedbackStore, ConversationClaims {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackSendRepository sends;
    private final FeedbackDecisionService decisions;
    private final FeedbackResponseService responses;
    private final FeedbackVisibilityService visibility;
    private final PersonDataWriteFence privacy;
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CopyWriteFence> claimConversationCopy(
            long workspaceId, UUID feedbackId, UUID mentorTurnId, int attempt) {
        var existing = feedback.findByIdAndWorkspaceId(feedbackId, workspaceId);
        if (existing.isEmpty() || !privacy.holdForAccountWrite(existing.get().getDeveloperId()))
            return Optional.empty();
        var row = lock(workspaceId, feedbackId);
        var attemptStartedAt = copies.runningAttemptStartedAt(workspaceId, row.getDeveloperId(), mentorTurnId, attempt);
        if (attemptStartedAt.isEmpty()
                || row.getDestination() != FeedbackDestination.CONVERSATION
                || visibility.withdrawn(workspaceId, feedbackId)) return Optional.empty();
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        if (children.size() != 1) return Optional.empty();
        var copy = children.getFirst();
        if (copy.getKind() != FeedbackCopyKind.CHAT_TURN) return Optional.empty();
        // A repeat in the same attempt keeps the fence it holds: the copy is that attempt's, and not settled.
        var held = copy.getWriteStartedAt();
        if (copy.getState() == FeedbackCopyState.WRITE_STARTED
                && mentorTurnId.equals(copy.getMentorTurnId())
                && held != null
                && !held.isBefore(attemptStartedAt.get())
                && copy.getPostedAt() == null
                && copy.getLastError() == null)
            return Optional.of(new CopyWriteFence(feedbackId, copy.getId(), mentorTurnId, held));
        if (row.getState() != FeedbackState.READY
                || !check(row, true).allowed()
                || copy.getState() != FeedbackCopyState.PLANNED
                || copies.claimConversation(workspaceId, feedbackId, copy.getId(), mentorTurnId) != 1)
            return Optional.empty();
        var recorded = copies.findByIdAndWorkspaceId(copy.getId(), workspaceId).orElseThrow();
        feedback.updateDelivery(
                workspaceId,
                feedbackId,
                FeedbackState.UNCONFIRMED,
                FeedbackStopReason.DELIVERY_UNCONFIRMED,
                row.getDeliveredAt());
        return Optional.of(new CopyWriteFence(
                feedbackId, copy.getId(), mentorTurnId, Objects.requireNonNull(recorded.getWriteStartedAt())));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConversationReceipt(long workspaceId, CopyWriteFence fence, ConversationCopyReceipt receipt) {
        if (!fence.mentorTurnId().equals(receipt.mentorTurnId()) || receipt.at().isBefore(fence.writeStartedAt()))
            throw new IllegalArgumentException("The receipt does not belong to this conversation write.");
        var existing =
                feedback.findByIdAndWorkspaceId(fence.feedbackId(), workspaceId).orElseThrow();
        if (!privacy.holdForAccountWrite(existing.getDeveloperId()))
            throw conflict("Recipient processing is suppressed.");
        var row = lock(workspaceId, fence.feedbackId());
        var copy = copies.lockByFeedbackId(workspaceId, fence.feedbackId()).stream()
                .filter(child -> child.getId().equals(fence.copyId()))
                .findFirst()
                .orElseThrow();
        if (row.getDestination() != FeedbackDestination.CONVERSATION
                || copy.getKind() != FeedbackCopyKind.CHAT_TURN
                || !Objects.equals(copy.getMentorTurnId(), fence.mentorTurnId())
                || !Objects.equals(copy.getWriteStartedAt(), fence.writeStartedAt()))
            throw conflict("The conversation write no longer has its recorded authority.");
        if (copy.getPostedAt() != null) return;
        switch (receipt.outcome()) {
            case DELIVERED -> {
                var entryId = receipt.mentorEntryId();
                if (entryId == null) throw conflict("A delivered conversation copy names the answer that showed it.");
                var words = copies.completedConversationText(
                                workspaceId, fence.mentorTurnId(), entryId, fence.feedbackId())
                        .filter(text -> !text.isBlank())
                        .orElseThrow(() -> conflict("The completed assistant words are unavailable."));
                if (copies.recordConversationDelivered(
                                workspaceId,
                                fence.feedbackId(),
                                fence.copyId(),
                                fence.mentorTurnId(),
                                entryId,
                                words,
                                receipt.at())
                        != 1) throw conflict("The conversation receipt could not be recorded.");
                feedback.updateDelivery(
                        workspaceId,
                        fence.feedbackId(),
                        FeedbackState.DELIVERED,
                        null,
                        row.getDeliveredAt() == null ? receipt.at() : row.getDeliveredAt());
            }
            case UNCONFIRMED, FAILED -> {
                boolean unknown = receipt.outcome() == ConversationCopyOutcome.UNCONFIRMED;
                if (copies.recordConversationOutcome(
                                workspaceId,
                                fence.feedbackId(),
                                fence.copyId(),
                                fence.mentorTurnId(),
                                receipt.mentorEntryId(),
                                unknown ? "WRITE_STARTED" : "FAILED",
                                unknown ? "DELIVERY_UNCONFIRMED" : null,
                                unknown ? FeedbackCopyReadback.UNKNOWN.name() : FeedbackCopyReadback.ABSENT.name(),
                                receipt.reason() == null ? "The conversation was not delivered." : receipt.reason())
                        != 1) throw conflict("The conversation receipt could not be recorded.");
                feedback.updateDelivery(
                        workspaceId,
                        fence.feedbackId(),
                        unknown ? FeedbackState.UNCONFIRMED : FeedbackState.FAILED,
                        unknown ? FeedbackStopReason.DELIVERY_UNCONFIRMED : null,
                        row.getDeliveredAt());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CopyWriteFence> unsettledConversationClaims(
            long workspaceId, UUID mentorTurnId, Instant since, Instant until) {
        return copies.findUnsettledConversationClaims(workspaceId, mentorTurnId, since, until).stream()
                .map(copy -> new CopyWriteFence(
                        copy.getFeedbackId(),
                        copy.getId(),
                        mentorTurnId,
                        Objects.requireNonNull(copy.getWriteStartedAt())))
                .toList();
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
        var send = sends.lockByFeedbackId(workspaceId, feedbackId);
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
        feedback.updateDelivery(
                workspaceId,
                feedbackId,
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
     * did not acknowledge. It records no decision and keeps the copies' receipts and the send's attempt history.
     */
    private void retryWithdrawal(Feedback row, Optional<FeedbackSend> send, List<FeedbackCopy> children) {
        if (send.filter(s -> s.getState() != FeedbackSendState.DONE).isPresent())
            throw conflict("The withdrawal is still in progress.");
        if (send.filter(s -> s.getAction() == FeedbackSendAction.RETRACT).isEmpty()
                || children.stream()
                        .noneMatch(c -> c.getState() == FeedbackCopyState.POSTED
                                && c.getExternalRef() != null
                                && c.getReadback() == FeedbackCopyReadback.TRANSIENT))
            throw conflict("No withdrawn copy can be retried.");
        sendQueue.queue(row, FeedbackSendAction.RETRACT, FeedbackSendState.PENDING);
    }

    public FeedbackCheck check(Feedback row, boolean approval) {
        return policy.check(row, FeedbackCheckStage.EGRESS, approval);
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
        policy.withhold(row, reason);
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
