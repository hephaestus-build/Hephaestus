package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Claims and settles the conversation copy of a piece of feedback, bound to the feedback's developer, who owns the
 * conversation rows that the claim and the receipt read. The claim joins the transaction of the tool call that holds
 * the turn; the receipt runs in its own.
 */
@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
class FeedbackConversationClaims implements ConversationClaims {
    private final FeedbackStoreService store;
    private final FeedbackRepository feedback;
    private final FeedbackOutcomes outcomes;
    private final FeedbackCopyRepository copies;
    private final FeedbackVisibilityService visibility;
    private final PersonDataWriteFence privacy;
    private final OwnerScope owners;

    @Override
    public Optional<CopyWriteFence> claimConversationCopy(
            long workspaceId, long accountId, UUID feedbackId, UUID conversationTurnId, int attempt) {
        // Refuses feedback of another person before the claim takes a lock. The same check after the feedback's lock
        // decides, because the feedback can change before that lock.
        var existing = feedback.findByIdAndWorkspaceId(feedbackId, workspaceId);
        if (existing.isEmpty()
                || existing.get().getDeveloperId() != accountId
                || existing.get().getDestination() != FeedbackDestination.CONVERSATION
                || !privacy.holdForAccountWrite(accountId)) return Optional.empty();
        owners.ofJob(accountId);
        var attemptStartedAt = copies.runningAttemptStartedAt(workspaceId, accountId, conversationTurnId, attempt);
        if (attemptStartedAt.isEmpty()) return Optional.empty();
        var row = store.lock(workspaceId, feedbackId);
        if (row.getDeveloperId() != accountId
                || row.getDestination() != FeedbackDestination.CONVERSATION
                || visibility.withdrawn(workspaceId, feedbackId)) return Optional.empty();
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        if (children.size() != 1) return Optional.empty();
        var copy = children.getFirst();
        if (copy.getKind() != FeedbackCopyKind.CHAT_TURN) return Optional.empty();
        // A repeat in the same attempt keeps the fence it holds: the copy is that attempt's, and not settled.
        var held = copy.getWriteStartedAt();
        if (copy.getState() == FeedbackCopyState.WRITE_STARTED
                && conversationTurnId.equals(copy.getConversationTurnId())
                && held != null
                && !held.isBefore(attemptStartedAt.get())
                && copy.getPostedAt() == null
                && copy.getLastError() == null)
            return Optional.of(new CopyWriteFence(feedbackId, copy.getId(), conversationTurnId, held));
        if (row.getState() != FeedbackState.READY
                || !store.check(row, true).allowed()
                || copy.getState() != FeedbackCopyState.PLANNED
                || copies.claimConversation(workspaceId, feedbackId, copy.getId(), conversationTurnId) != 1)
            return Optional.empty();
        var recorded = copies.findByIdAndWorkspaceId(copy.getId(), workspaceId).orElseThrow();
        outcomes.update(row, FeedbackState.UNCONFIRMED, FeedbackStopReason.DELIVERY_UNCONFIRMED, row.getDeliveredAt());
        return Optional.of(new CopyWriteFence(
                feedbackId, copy.getId(), conversationTurnId, Objects.requireNonNull(recorded.getWriteStartedAt())));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConversationReceipt(long workspaceId, CopyWriteFence fence, ConversationCopyReceipt receipt) {
        if (!fence.conversationTurnId().equals(receipt.conversationTurnId())
                || receipt.at().isBefore(fence.writeStartedAt()))
            throw new IllegalArgumentException("The receipt does not belong to this conversation write.");
        if (receipt.outcome() != ConversationCopyOutcome.DELIVERED && receipt.conversationEntryId() != null)
            throw new IllegalArgumentException("Only a delivered receipt names the answer that showed the copy.");
        var existing =
                feedback.findByIdAndWorkspaceId(fence.feedbackId(), workspaceId).orElseThrow();
        if (!privacy.holdForAccountWrite(existing.getDeveloperId()))
            throw conflict("Recipient processing is suppressed.");
        var row = store.lock(workspaceId, fence.feedbackId());
        var copy = copies.lockByFeedbackId(workspaceId, fence.feedbackId()).stream()
                .filter(child -> child.getId().equals(fence.copyId()))
                .findFirst()
                .orElseThrow();
        if (row.getDestination() != FeedbackDestination.CONVERSATION
                || copy.getKind() != FeedbackCopyKind.CHAT_TURN
                || !Objects.equals(copy.getConversationTurnId(), fence.conversationTurnId())
                || !Objects.equals(copy.getWriteStartedAt(), fence.writeStartedAt()))
            throw conflict("The conversation write no longer has its recorded authority.");
        if (copy.getPostedAt() != null) return;
        owners.ofJob(row.getDeveloperId());
        switch (receipt.outcome()) {
            case DELIVERED -> {
                var entryId = receipt.conversationEntryId();
                if (entryId == null) throw conflict("A delivered conversation copy names the answer that showed it.");
                String unavailable = "The completed assistant words are unavailable.";
                // The receipt keeps no words: the answer shows them, until a redaction erases them with it.
                if (copies.completedConversationText(
                                workspaceId, fence.conversationTurnId(), entryId, fence.feedbackId())
                        .filter(text -> !text.isBlank())
                        .isEmpty()) throw conflict(unavailable);
                int recorded;
                try {
                    recorded = copies.recordConversationDelivered(
                            workspaceId,
                            fence.feedbackId(),
                            fence.copyId(),
                            fence.conversationTurnId(),
                            entryId,
                            receipt.at());
                } catch (DataAccessException failure) {
                    // The copy's guard checks the answer's words again, as of its own read. It refuses a redaction
                    // that it sees; a redaction that commits later leaves a posted receipt whose answer shows no words.
                    if (sqlState(failure, "23514"))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, unavailable, failure);
                    throw failure;
                }
                if (recorded != 1) throw conflict("The conversation receipt could not be recorded.");
                outcomes.update(
                        row,
                        FeedbackState.DELIVERED,
                        null,
                        row.getDeliveredAt() == null ? receipt.at() : row.getDeliveredAt());
            }
            case UNCONFIRMED, FAILED -> {
                boolean unknown = receipt.outcome() == ConversationCopyOutcome.UNCONFIRMED;
                recordUnfulfilledConversationReceipt(workspaceId, fence, receipt, unknown);
                outcomes.update(
                        row,
                        unknown ? FeedbackState.UNCONFIRMED : FeedbackState.FAILED,
                        unknown ? FeedbackStopReason.DELIVERY_UNCONFIRMED : null,
                        row.getDeliveredAt());
            }
        }
    }

    private void recordUnfulfilledConversationReceipt(
            long workspaceId, CopyWriteFence fence, ConversationCopyReceipt receipt, boolean unknown) {
        if (copies.recordConversationOutcome(
                        workspaceId,
                        fence.feedbackId(),
                        fence.copyId(),
                        fence.conversationTurnId(),
                        unknown ? "WRITE_STARTED" : "FAILED",
                        unknown ? "DELIVERY_UNCONFIRMED" : null,
                        unknown ? FeedbackCopyReadback.UNKNOWN.name() : FeedbackCopyReadback.ABSENT.name(),
                        receipt.reason() == null ? "The conversation was not delivered." : receipt.reason())
                != 1) throw conflict("The conversation receipt could not be recorded.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Unsettled> unsettledConversationClaims(
            long workspaceId, UUID conversationTurnId, Instant since, Instant until) {
        return copies.findUnsettledConversationClaims(workspaceId, conversationTurnId, since, until).stream()
                .map(claim -> new Unsettled(
                        new CopyWriteFence(
                                claim.getFeedbackId(),
                                claim.getCopyId(),
                                conversationTurnId,
                                claim.getWriteStartedAt()),
                        claim.getPersonAccountId()))
                .toList();
    }

    private static boolean sqlState(DataAccessException failure, String state) {
        return failure.getMostSpecificCause() instanceof SQLException sql && state.equals(sql.getSQLState());
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
