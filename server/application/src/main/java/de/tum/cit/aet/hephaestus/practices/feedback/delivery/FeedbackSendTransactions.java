package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
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
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackSendTransactions {
    /** How long an unresolved send waits before it reads the provider back again. */
    private static final Duration REREAD = Duration.ofSeconds(30);

    private final FeedbackStoreService store;
    private final FeedbackOutcomes outcomes;
    private final FeedbackCopyRepository copies;
    private final AgentActions actions;
    private final FeedbackVisibilityService visibility;
    private final Clock clock;
    private final PersonDataWriteFence privacy;
    private final FeedbackSendQueue sendQueue;
    private final ItemIndexService itemLocks;
    private final FeedbackPublicationContext publication;
    private final FeedbackRepository feedback;

    /**
     * The copies are in position order, the order a send writes them in. The claim locks them in id order, which a
     * hash of the feedback decides, so a withdrawal that lands during the first write would otherwise spare a different
     * copy on each run. The position is unique within a feedback.
     */
    public record Attempt(Feedback feedback, FeedbackSendAction action, List<FeedbackCopy> copies, int attempts) {
        public Attempt {
            copies = copies.stream()
                    .sorted(Comparator.comparingInt(FeedbackCopy::getPosition))
                    .toList();
        }
    }

    /** The feedback and its copies as the claimed send finds them; {@code null} when the feedback is gone. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable Attempt claim(long workspaceId, UUID feedbackId, ActionAttempt owner) {
        var row = feedback.lockByIdAndWorkspaceId(feedbackId, workspaceId);
        if (row.isEmpty()) return null;
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        return new Attempt(row.get(), FeedbackSendQueue.actionOf(owner.idempotencyKey()), children, owner.attempt());
    }

    /**
     * Commits the copies' write fences before any provider write. A new create first holds the work's item lock in its
     * own statement, the lock every create on the work takes; one later statement then reads the publication context
     * and any unresolved write of another review from the same snapshot. A receipt that commits after that snapshot
     * can only resolve a write this decision already saw as unresolved. The same transaction holds the developer's
     * person-data write fence and commits before the provider call, so no erasure or account change waits on the
     * provider. A refused hold withholds the feedback and returns false: the writers swallow an exception from here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fence(
            long workspaceId,
            UUID feedbackId,
            ActionAttempt owner,
            List<UUID> attempting,
            List<CopyReceipt> completed) {
        if (!attempting.isEmpty())
            publication.itemOf(workspaceId, feedbackId).ifPresent(itemId -> itemLocks.lock(workspaceId, itemId));
        var recipient = feedback.developerOf(feedbackId, workspaceId);
        if (recipient.isEmpty()) return false;
        boolean admitted = privacy.holdForAccountWrite(recipient.get());
        feedback.lockCorrectionFamily(workspaceId, feedbackId);
        var row = store.lock(workspaceId, feedbackId);
        if (row.getDeveloperId() != recipient.get())
            throw new IllegalStateException("The feedback changed its developer.");
        if (!owns(owner)) return false;
        record(row, completed);
        if (!admitted) {
            store.withhold(row, FeedbackStopReason.PERSON_SUPPRESSED);
            return false;
        }
        if (visibility.withdrawn(workspaceId, feedbackId)) return false;
        if (row.getComposition().hasNonNull("editOf")) {
            var previous = store.lock(
                    workspaceId,
                    UUID.fromString(row.getComposition().path("editOf").asString()));
            if (visibility.withdrawn(workspaceId, previous.getId())
                    || previous.getComposition().path("humanControlled").asBoolean()) {
                store.withhold(row, FeedbackStopReason.DELIVERY_UNCONFIRMED);
                return false;
            }
        }
        var check = store.check(row, true);
        if (!check.allowed()) {
            store.withhold(row, Objects.requireNonNull(check.reason()));
            return false;
        }
        var action = FeedbackSendQueue.actionOf(owner.idempotencyKey());
        if (!attempting.isEmpty() && (action == FeedbackSendAction.POST || action == FeedbackSendAction.EDIT)) {
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
        outcomes.update(
                row, FeedbackState.UNCONFIRMED, FeedbackStopReason.DELIVERY_UNCONFIRMED, projection.deliveredAt());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void receipt(long workspaceId, UUID feedbackId, ActionAttempt owner, List<CopyReceipt> receipts) {
        feedback.lockCorrectionFamily(workspaceId, feedbackId);
        var row = store.lock(workspaceId, feedbackId);
        if (owns(owner)) {
            record(row, receipts);
            var projection = FeedbackSendStateMachine.project(
                    row, copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId));
            outcomes.update(row, projection.state(), projection.stopReason(), projection.deliveredAt());
        }
    }

    private void record(Feedback row, List<CopyReceipt> receipts) {
        long workspaceId = row.getWorkspaceId();
        UUID feedbackId = row.getId();
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
                        kind.name(),
                        receipt.threadRef());
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
        if (!receipts.isEmpty()
                && row.getComposition().hasNonNull("editOf")
                && copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, feedbackId).stream()
                        .allMatch(copy -> copy.getState() == FeedbackCopyState.POSTED)) {
            var previous = store.lock(
                    workspaceId,
                    UUID.fromString(row.getComposition().path("editOf").asString()));
            outcomes.update(previous, FeedbackState.REPLACED, null, previous.getDeliveredAt());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void withholdCopies(
            long workspaceId, UUID feedbackId, ActionAttempt owner, List<UUID> copyIds, FeedbackStopReason reason) {
        store.lock(workspaceId, feedbackId);
        if (!owns(owner)) return;
        for (UUID copyId : copyIds) copies.withholdCopy(workspaceId, feedbackId, copyId, reason.name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void absent(long workspaceId, UUID feedbackId, ActionAttempt owner, UUID copyId) {
        store.lock(workspaceId, feedbackId);
        if (owns(owner))
            copies.recordAbsent(workspaceId, feedbackId, copyId, "Provider readback proved the copy is absent.");
    }

    /**
     * Settles the send's copies. While a copy's write may have reached the provider, the send reads it back again
     * later; its last attempt then ends it uncertain. The correction that a settled send may still owe is decided when
     * the action ends ({@link #ended}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActionResult finish(long workspaceId, UUID feedbackId, ActionAttempt owner) {
        var row = store.lock(workspaceId, feedbackId);
        if (!owns(owner)) return new ActionResult.Abandoned();
        boolean retract = FeedbackSendQueue.actionOf(owner.idempotencyKey()) == FeedbackSendAction.RETRACT;
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        boolean unresolved = children.stream()
                .anyMatch(c -> c.getState() == FeedbackCopyState.WRITE_STARTED
                        || (retract && c.getReadback() == FeedbackCopyReadback.TRANSIENT));
        project(row, children, unresolved);
        return unresolved ? new ActionResult.Reread(REREAD) : new ActionResult.Done(null);
    }

    /**
     * Ends a send's domain side in the transaction that ends its action. A send whose copies are resolved queues the
     * correction of a mark that changed under it, also when it ended without settling. A decision or a mark that commits while the send runs sees the send running and leaves
     * the correction to it; deciding here, under the feedback lock and with the action already ended, sees every such
     * change. A send that ended without settling, such as one whose lease ran out on its last attempt, projects its
     * copies.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(long workspaceId, UUID feedbackId, ActionState state) {
        var row = feedback.lockByIdAndWorkspaceId(feedbackId, workspaceId);
        if (row.isEmpty()) return;
        var children = copies.lockByFeedbackId(workspaceId, feedbackId);
        boolean unresolved = children.stream().anyMatch(c -> c.getState() == FeedbackCopyState.WRITE_STARTED);
        if (state != ActionState.DONE) project(row.get(), children, unresolved);
        if (!unresolved && asksForCorrection(row.get(), copies.lockByFeedbackId(workspaceId, feedbackId)))
            sendQueue.follow(row.get(), FeedbackSendAction.RETRACT);
    }

    private void project(Feedback row, List<FeedbackCopy> children, boolean unresolved) {
        long workspaceId = row.getWorkspaceId();
        UUID feedbackId = row.getId();
        // A finished send leaves no copy planned: nothing sends it later, and a retry needs it failed.
        boolean failed = !unresolved
                && !visibility.withdrawn(workspaceId, feedbackId)
                && copies.failUnstarted(workspaceId, feedbackId) > 0;
        var projection = FeedbackSendStateMachine.project(
                row, failed ? copies.lockByFeedbackId(workspaceId, feedbackId) : children);
        outcomes.update(row, projection.state(), projection.stopReason(), projection.deliveredAt());
    }

    /**
     * Whether a mark changed under this send. A copy with a recorded error had its correction tried, so it asks no
     * more, which ends the sequence of sends.
     */
    private boolean asksForCorrection(Feedback row, List<FeedbackCopy> children) {
        long workspaceId = row.getWorkspaceId();
        boolean withdrawn = visibility.withdrawn(workspaceId, row.getId());
        var incorrect = copies.incorrectCopyIds(workspaceId, row.getId());
        boolean remove = children.stream()
                .anyMatch(c -> c.getState() == FeedbackCopyState.POSTED
                        && c.getLastError() == null
                        && (withdrawn || incorrect.contains(c.getId())));
        if (remove) return true;
        return !withdrawn
                && children.stream()
                        .anyMatch(c -> c.getState() == FeedbackCopyState.RETRACTED
                                && c.getLastError() == null
                                && !incorrect.contains(c.getId()))
                && store.check(row, true).allowed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(long workspaceId, UUID feedbackId, ActionAttempt owner) {
        store.lock(workspaceId, feedbackId);
        if (!owns(owner)) return;
        if (FeedbackSendQueue.actionOf(owner.idempotencyKey()) == FeedbackSendAction.RETRACT) {
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

    private boolean owns(ActionAttempt owner) {
        return actions.holds(owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void correction(long workspaceId, UUID feedbackId, ActionAttempt owner, UUID copyId, String state) {
        store.lock(workspaceId, feedbackId);
        if (owns(owner)) copies.confirmCorrection(workspaceId, feedbackId, copyId, state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void correctionError(
            long workspaceId,
            UUID feedbackId,
            ActionAttempt owner,
            UUID copyId,
            @Nullable FeedbackCopyReadback readback,
            String error) {
        store.lock(workspaceId, feedbackId);
        if (owns(owner))
            copies.recordError(workspaceId, feedbackId, copyId, null, readback == null ? null : readback.name(), error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean mayRestore(long workspaceId, UUID feedbackId, ActionAttempt owner) {
        var row = store.lock(workspaceId, feedbackId);
        return owns(owner)
                && !visibility.withdrawn(workspaceId, feedbackId)
                && store.check(row, true).allowed();
    }

    /**
     * Admits one provider correction: holds the developer's person-data write fence in this transaction, which commits
     * before the provider call. A refused hold withholds the feedback with {@code PERSON_SUPPRESSED}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean admitCorrection(long workspaceId, UUID feedbackId, ActionAttempt owner) {
        var recipient = feedback.developerOf(feedbackId, workspaceId);
        if (recipient.isEmpty()) return false;
        boolean admitted = privacy.holdForAccountWrite(recipient.get());
        var row = store.lock(workspaceId, feedbackId);
        if (row.getDeveloperId() != recipient.get())
            throw new IllegalStateException("The feedback changed its developer.");
        if (!owns(owner)) return false;
        if (!admitted) {
            store.withhold(row, FeedbackStopReason.PERSON_SUPPRESSED);
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refuseTarget(long workspaceId, UUID feedbackId, ActionAttempt owner, FeedbackStopReason reason) {
        var row = store.lock(workspaceId, feedbackId);
        if (owns(owner)) store.withhold(row, reason);
    }
}
