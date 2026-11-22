package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    /** Where a review that a comment requested answers that comment when no feedback does. */
    static final String COMMENT_ANSWER = "commentAnswer";

    private final ReviewStart starts;
    private final ReviewSnapshotQueryService snapshots;
    private final WorkspaceQueries workspaces;
    private final ProductItemQuery items;
    private final ReviewRequestTransactions transactions;
    private final ReviewPlanner planner;
    private final ReviewReadinessEdge readinessEdge;
    private final ReviewSourceReads sourceReads;
    private final ReviewRequestAdmission requestAdmission;
    private final ReviewRecorder recorder;
    private final ReviewTermination termination;

    /**
     * Admits the request without a lock. Then it pins the item, judges the item's results and reads the sources that
     * the plan queues with no transaction, since each can fetch from the provider. It records the review under the
     * workspace lock, which admits and plans the request again. A replay returns its review without a pin.
     */
    @Override
    public UUID request(ReviewRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A review request runs only outside a database transaction");
        validate(request);
        return switch (transactions.read(() -> requestAdmission.admit(request, workspace(request)))) {
            case ReviewRequestAdmission.Admission.Replay replay -> replay.reviewId();
            case ReviewRequestAdmission.Admission.Admitted admitted -> {
                var pinned = pin(request, admitted.item());
                var early = earlyRead(request, admitted.item(), pinned);
                yield transactions.write(() -> {
                    var workspace =
                            workspaces.findByIdForUpdate(request.workspaceId()).orElseThrow();
                    return switch (requestAdmission.admit(request, workspace)) {
                        case ReviewRequestAdmission.Admission.Replay replay -> replay.reviewId();
                        case ReviewRequestAdmission.Admission.Admitted locked -> {
                            requirePinnedVersion(request, pinned);
                            yield recorder.record(
                                    request,
                                    workspace,
                                    pinned,
                                    planner.plan(request, workspace, locked.item(), pinned, early.currentness()),
                                    early);
                        }
                    };
                });
            }
        };
    }

    /**
     * The pin ran before the lock, so a newer version of the work may have arrived since, and its event review may
     * already measure it. Under the lock the stored version must still be the pinned one. An event review pins its
     * occasion's version, which {@link ReviewPlanner#plan} checks against the occasion.
     */
    private void requirePinnedVersion(ReviewRequest request, ItemSnapshot pinned) {
        if (request.kind() != ReviewKind.ITEM || occasion(request) != null) return;
        if (items.currentWorkVersion(request.workspaceId(), request.itemId())
                        instanceof ProductItemQuery.CurrentWorkVersion.Known current
                && !current.value().equals(pinned.version().value()))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.VERSION_CHANGED,
                    "The work changed while the review was requested. Ask for the review again.");
    }

    private ReviewWorkspace workspace(ReviewRequest request) {
        return workspaces.findById(request.workspaceId()).orElseThrow();
    }

    private ItemSnapshot pin(ReviewRequest request, ItemRef ref) {
        // An event review keeps the occasion's own version, state and roles once its source retained them. Every
        // decision after the pin still reads the workspace, its members and their choices as they are now.
        var occasion = occasion(request);
        var requestedVersion = request.version();
        try {
            return occasion != null && requestedVersion != null
                    ? items.pinOccasion(ref, requestedVersion, occasion)
                    : items.pin(ref, requestedVersion);
        } catch (ItemCaptureException unavailable) {
            // Settled here, not in the listener: the listener fails on any other exception, and its event is sent
            // again.
            if (unavailable.code() != ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE) throw unavailable;
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "The occasion kept no record of the work as it was.",
                    ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE,
                    unavailable);
        }
    }

    private ReviewSourceReads.EarlyRead earlyRead(ReviewRequest request, ItemRef ref, ItemSnapshot pinned) {
        if (request.kind() != ReviewKind.ITEM) return ReviewSourceReads.EarlyRead.NONE;
        var currentness = readinessEdge.currentness(request, pinned);
        var kinds = transactions.read(() -> sourceReads.readKinds(
                pinned,
                planner.plan(request, workspace(request), ref, pinned, currentness).gated().stream()
                        .map(practice -> planner.version(request, practice))
                        .toList()));
        return new ReviewSourceReads.EarlyRead(
                currentness, kinds, items.fingerprints(pinned.item(), kinds, occasion(request)));
    }

    static @Nullable UUID occasion(ReviewRequest request) {
        return request.origin() == ReviewOrigin.EVENT ? request.eventId() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewSnapshot> find(long workspaceId, UUID reviewId) {
        return snapshots.find(workspaceId, reviewId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PinnedPractice>> findPinned(long workspaceId, Collection<UUID> reviewIds) {
        return snapshots.findPinned(workspaceId, reviewIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> publicReviewedAt(long workspaceId, UUID reviewId) {
        return snapshots.publicReviewedAt(workspaceId, reviewId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> budgetUsd(long workspaceId, UUID reviewId) {
        return snapshots.budgetUsd(workspaceId, reviewId);
    }

    @Override
    @Transactional
    public boolean start(long workspaceId, UUID reviewId) {
        return starts.start(workspaceId, reviewId);
    }

    @Override
    @Transactional
    public void complete(long workspaceId, UUID reviewId, ReviewCompletion completion) {
        termination.complete(workspaceId, reviewId, completion);
    }

    @Override
    @Transactional
    public boolean cancel(long workspaceId, UUID reviewId) {
        return termination.cancel(workspaceId, reviewId);
    }

    private static void validate(ReviewRequest r) {
        if (r.workspaceId() <= 0
                || r.itemId() <= 0
                || r.practiceIds().stream().distinct().count()
                        != r.practiceIds().size())
            throw new InvalidInputException("Use positive item ids and distinct practices.");
        if ((r.origin() == ReviewOrigin.EVENT && r.eventId() == null)
                || ((r.origin() == ReviewOrigin.REQUEST || r.origin() == ReviewOrigin.TRIAL)
                        && r.requestedById() == null)
                || (r.origin() == ReviewOrigin.TRIAL
                        && (r.kind() != ReviewKind.ITEM
                                || r.practiceIds().size() != 1
                                || r.requestKey() != null
                                || r.commentAnswer() != null))
                || (r.origin() != ReviewOrigin.TRIAL && r.practiceVersionId() != null)
                || ((r.origin() == ReviewOrigin.SCHEDULE || r.origin() == ReviewOrigin.BACKFILL)
                        && r.batchId() == null))
            throw new IllegalArgumentException("The review origin requires its recorded link.");
        if (r.kind() == ReviewKind.SCAN
                && r.origin() == ReviewOrigin.REQUEST
                && (r.requestKey() == null || r.requestDigest() == null))
            throw new IllegalArgumentException("A scan requires its request key and digest.");
    }
}
