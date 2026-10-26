package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyReadback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackSendTransactions {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final FeedbackStoreService store;
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackSendRepository sends;
    private final FeedbackVisibilityService visibility;
    private final Clock clock;
    private final PersonDataWriteFence privacy;
    private final FeedbackSendQueue sendQueue;
    private final ItemIndexService itemLocks;
    private final FeedbackPublicationContext publication;

    public record Attempt(Feedback feedback, FeedbackSendAction action, List<FeedbackCopy> copies, int attempts) {
        public Attempt {
            copies = List.copyOf(copies);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable Attempt claim(long workspaceId, UUID feedbackId, String owner) {
        var row = store.lock(workspaceId, feedbackId);
        var prior = sends.lockByFeedbackId(workspaceId, feedbackId);
        Instant now = clock.instant();
        if (prior.isEmpty()
                || prior.get().getState() == FeedbackSendState.DONE
                || sends.claim(workspaceId, feedbackId, owner, now, now.plus(LEASE)) != 1) return null;
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        return new Attempt(row, prior.get().getAction(), children, prior.get().getAttempts() + 1);
    }

    /**
     * Commits the copies' write fences before any provider write. A new create first holds the work's item lock in its
     * own statement, the lock every create on the work takes; one later statement then reads the publication context
     * and any unresolved write of another review from the same snapshot. A receipt that commits after that snapshot
     * can only resolve a write this decision already saw as unresolved.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fence(
            long workspaceId, UUID feedbackId, String owner, List<UUID> attempting, List<CopyReceipt> completed) {
        if (!attempting.isEmpty())
            publication.itemOf(workspaceId, feedbackId).ifPresent(itemId -> itemLocks.lock(workspaceId, itemId));
        var row = store.lock(workspaceId, feedbackId);
        if (!owns(workspaceId, feedbackId, owner)) return false;
        record(workspaceId, feedbackId, completed);
        if (visibility.withdrawn(workspaceId, feedbackId)) {
            settle(workspaceId, feedbackId, owner, false);
            return false;
        }
        var check = store.check(row, true);
        if (!check.allowed()) {
            store.withhold(row, Objects.requireNonNull(check.reason()));
            return false;
        }
        if (!attempting.isEmpty()) {
            var refusal = publication.refusal(row);
            if (refusal.isPresent()) {
                store.withhold(row, refusal.get());
                return false;
            }
        }
        for (UUID id : attempting) {
            if (copies.beginWrite(workspaceId, feedbackId, id, clock.instant()) != 1)
                throw new IllegalStateException("A copy is no longer eligible for a write.");
        }
        var projection = FeedbackSendStateMachine.project(
                row, copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId));
        feedback.updateDelivery(
                workspaceId,
                feedbackId,
                FeedbackState.UNCONFIRMED,
                FeedbackStopReason.DELIVERY_UNCONFIRMED,
                projection.deliveredAt());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void receipt(long workspaceId, UUID feedbackId, String owner, List<CopyReceipt> receipts) {
        var row = store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner)) {
            record(workspaceId, feedbackId, receipts);
            var projection = FeedbackSendStateMachine.project(
                    row, copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId));
            feedback.updateDelivery(
                    workspaceId, feedbackId, projection.state(), projection.stopReason(), projection.deliveredAt());
        }
    }

    private void record(long workspaceId, UUID feedbackId, List<CopyReceipt> receipts) {
        for (CopyReceipt receipt : receipts) {
            var copy = copies.findByIdAndWorkspaceId(receipt.copyId(), workspaceId)
                    .filter(c -> c.getFeedbackId().equals(feedbackId))
                    .orElseThrow();
            if (copy.getState() == FeedbackCopyState.POSTED || copy.getState() == FeedbackCopyState.RETRACTED) {
                if (receipt.externalRef() != null && !Objects.equals(copy.getExternalRef(), receipt.externalRef()))
                    throw new IllegalStateException("A receipt cannot replace an existing provider reference.");
                continue;
            }
            if (receipt.disposition() != CopyDisposition.FAILED) {
                if (receipt.externalRef() == null
                        || receipt.externalRef().isBlank()
                        || copy.getState() != FeedbackCopyState.WRITE_STARTED
                        || copy.getWriteStartedAt() == null)
                    throw new IllegalArgumentException(
                            "A posted provider copy needs its committed fence and reference.");
                if (receipt.actualKind() != null
                        && ((copy.getKind() != FeedbackCopyKind.LINE_NOTE
                                        && copy.getKind() != FeedbackCopyKind.FILE_NOTE)
                                || (copy.getKind() == FeedbackCopyKind.FILE_NOTE
                                        && receipt.actualKind() != CopyForm.FILE_NOTE)))
                    throw new IllegalArgumentException("A receipt cannot change its authored copy form.");
                FeedbackCopyKind kind =
                        receipt.actualKind() == CopyForm.FILE_NOTE ? FeedbackCopyKind.FILE_NOTE : copy.getKind();
                copies.confirmPosted(
                        workspaceId,
                        feedbackId,
                        copy.getId(),
                        clock.instant(),
                        receipt.externalRef(),
                        receipt.externalUrl(),
                        kind.name());
            } else if (Boolean.FALSE.equals(receipt.writeMayHaveStarted())) {
                copies.recordAbsent(workspaceId, feedbackId, copy.getId(), "Provider proved the copy was not created.");
            } else
                copies.recordError(
                        workspaceId,
                        feedbackId,
                        copy.getId(),
                        FeedbackStopReason.DELIVERY_UNCONFIRMED.name(),
                        FeedbackCopyReadback.UNKNOWN.name(),
                        "Provider visibility is unknown.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void withholdCopies(
            long workspaceId, UUID feedbackId, String owner, List<UUID> copyIds, FeedbackStopReason reason) {
        store.lock(workspaceId, feedbackId);
        if (!owns(workspaceId, feedbackId, owner)) return;
        for (UUID copyId : copyIds) copies.withholdCopy(workspaceId, feedbackId, copyId, reason.name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void absent(long workspaceId, UUID feedbackId, String owner, UUID copyId) {
        store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner))
            copies.recordAbsent(workspaceId, feedbackId, copyId, "Provider readback proved the copy is absent.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long workspaceId, UUID feedbackId, String owner, boolean exhausted) {
        settle(workspaceId, feedbackId, owner, exhausted);
    }

    private void settle(long workspaceId, UUID feedbackId, String owner, boolean exhausted) {
        var row = store.lock(workspaceId, feedbackId);
        if (!owns(workspaceId, feedbackId, owner)) return;
        var active = sends.lockByFeedbackId(workspaceId, feedbackId).orElseThrow();
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        boolean unresolved = children.stream()
                .anyMatch(c -> c.getState() == FeedbackCopyState.WRITE_STARTED
                        || (active.getAction() == FeedbackSendAction.RETRACT
                                && c.getReadback() == FeedbackCopyReadback.TRANSIENT));
        // A finished post leaves no copy planned: nothing sends it later, and a retry needs it failed.
        if (active.getAction() == FeedbackSendAction.POST
                && !unresolved
                && !visibility.withdrawn(workspaceId, feedbackId)
                && copies.failUnstarted(workspaceId, feedbackId) > 0)
            children = copies.lockByFeedbackId(workspaceId, feedbackId);
        var projection = FeedbackSendStateMachine.project(row, children);
        Instant now = clock.instant();
        if (sends.settle(
                        workspaceId,
                        feedbackId,
                        owner,
                        unresolved && !exhausted ? FeedbackSendState.UNCERTAIN.name() : FeedbackSendState.DONE.name(),
                        now,
                        now.plusSeconds(30),
                        unresolved ? "Provider visibility is unknown." : null)
                != 1) return;
        feedback.updateDelivery(
                workspaceId, feedbackId, projection.state(), projection.stopReason(), projection.deliveredAt());
        if (active.getAction() == FeedbackSendAction.POST
                && !unresolved
                && children.stream().anyMatch(c -> c.getState() == FeedbackCopyState.POSTED)
                && (visibility.withdrawn(workspaceId, feedbackId)
                        || store.check(row, true).reason() == FeedbackStopReason.RESULT_MARKED_INCORRECT))
            sendQueue.queue(row, FeedbackSendAction.RETRACT, FeedbackSendState.PENDING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(long workspaceId, UUID feedbackId, String owner) {
        store.lock(workspaceId, feedbackId);
        if (!owns(workspaceId, feedbackId, owner)) return;
        var active = sends.lockByFeedbackId(workspaceId, feedbackId).orElseThrow();
        if (active.getAction() == FeedbackSendAction.RETRACT) {
            for (var copy : copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId)) {
                if (copy.getState() == FeedbackCopyState.POSTED || copy.getState() == FeedbackCopyState.RETRACTED)
                    copies.recordError(
                            workspaceId,
                            feedbackId,
                            copy.getId(),
                            null,
                            FeedbackCopyReadback.TRANSIENT.name(),
                            "Provider correction call did not produce an" + " acknowledgement.");
            }
            return;
        }
        copies.failUnstarted(workspaceId, feedbackId);
        for (var copy : copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId))
            if (copy.getState() == FeedbackCopyState.WRITE_STARTED)
                copies.recordError(
                        workspaceId,
                        feedbackId,
                        copy.getId(),
                        FeedbackStopReason.DELIVERY_UNCONFIRMED.name(),
                        FeedbackCopyReadback.UNKNOWN.name(),
                        "Provider call did not produce a confirmed receipt.");
    }

    private boolean owns(long workspaceId, UUID feedbackId, String owner) {
        var send = sends.lockByFeedbackId(workspaceId, feedbackId);
        return send.filter(s -> s.getState() == FeedbackSendState.SENDING
                        && owner.equals(s.getLeaseOwner())
                        && s.getLeaseExpiresAt() != null
                        && s.getLeaseExpiresAt().isAfter(clock.instant()))
                .isPresent();
    }

    @Transactional
    public <T extends @Nullable Object> T recipientWrite(long developerId, Supplier<T> write) {
        if (!privacy.holdForAccountWrite(developerId)) throw new RecipientWriteRefused();
        return write.get();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void correction(long workspaceId, UUID feedbackId, String owner, UUID copyId, String state) {
        store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner)) copies.confirmCorrection(workspaceId, feedbackId, copyId, state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void correctionError(
            long workspaceId,
            UUID feedbackId,
            String owner,
            UUID copyId,
            @Nullable FeedbackCopyReadback readback,
            String error) {
        store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner))
            copies.recordError(workspaceId, feedbackId, copyId, null, readback == null ? null : readback.name(), error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean mayRestore(long workspaceId, UUID feedbackId, String owner) {
        var row = store.lock(workspaceId, feedbackId);
        return owns(workspaceId, feedbackId, owner)
                && !visibility.withdrawn(workspaceId, feedbackId)
                && store.check(row, true).allowed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean holdsLease(long workspaceId, UUID feedbackId, String owner) {
        store.lock(workspaceId, feedbackId);
        return owns(workspaceId, feedbackId, owner);
    }

    public static final class RecipientWriteRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RecipientWriteRefused() {
            super("Recipient processing is suppressed.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refuseRecipient(long workspaceId, UUID feedbackId, String owner) {
        var row = store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner)) store.withhold(row, FeedbackStopReason.PERSON_SUPPRESSED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refuseTarget(long workspaceId, UUID feedbackId, String owner, FeedbackStopReason reason) {
        var row = store.lock(workspaceId, feedbackId);
        if (owns(workspaceId, feedbackId, owner)) store.withhold(row, reason);
    }
}
