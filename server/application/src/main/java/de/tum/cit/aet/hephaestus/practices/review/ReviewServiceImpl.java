package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCancellationRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository memberships;
    private final PracticeRepository practices;
    private final ReviewSnapshotQueryService snapshots;
    private final WorkspaceRepository workspaces;
    private final ProductItemQuery items;
    private final PracticeReviewCoverageService coverage;
    private final ReviewGate gate;
    private final AccountIdentityQuery identities;
    private final ReviewModelBindings bindings;
    private final ReviewBudget budget;
    private final ReviewRuntimeQuery runtime;
    private final ProductItemRepository itemRows;
    private final JsonMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ScanReportService reports;

    @Override
    @Transactional(noRollbackFor = ReviewRequestRejected.class)
    public UUID request(ReviewRequest request) {
        validate(request);
        var workspace = workspaces.findByIdForUpdate(request.workspaceId()).orElseThrow();
        if (request.requestKey() != null) {
            var existing = reviews.findByWorkspaceIdAndRequestKey(request.workspaceId(), request.requestKey());
            if (existing.isPresent()) {
                if (!Objects.equals(existing.orElseThrow().getRequestDigest(), request.requestDigest()))
                    throw replayConflict(existing.orElseThrow());
                return existing.orElseThrow().getId();
            }
        }
        if (request.eventId() != null) {
            var prior = reviews.findByWorkspaceIdAndEventId(request.workspaceId(), request.eventId());
            if (prior.isPresent()) return prior.orElseThrow().getId();
        }
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.WORKSPACE_INACTIVE, "The workspace is inactive.");
        gate.refusal(workspace, request.origin()).ifPresent(skip -> {
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE, skip.reason(), skip.eventReason());
        });
        if (request.kind() == ReviewKind.SCAN) {
            var active = reviews.findFirstByWorkspaceIdAndKindAndStatusInOrderByCreatedAtAsc(
                    request.workspaceId(), ReviewKind.SCAN, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING));
            if (active.isPresent()) {
                var refusal = new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.SCAN_ACTIVE, "A scan is already active in this workspace.");
                refusal.getBody()
                        .setProperty("activeScanId", active.orElseThrow().getId());
                throw refusal;
            }
        }
        if (!budget.canRequest(request.workspaceId(), request.kind(), request.budgetUsd()))
            throw ReviewRequestRejected.budgetUnavailable();
        var ref = items.require(request.workspaceId(), request.itemId());
        // An event review keeps the occasion's own version, state and roles once its source retained them. Every
        // decision below still reads the workspace, its members and their choices as they are now.
        var occasion = request.origin() == ReviewOrigin.EVENT ? request.eventId() : null;
        var requestedVersion = request.version();
        ItemSnapshot pinned;
        try {
            pinned = occasion != null && requestedVersion != null
                    ? items.pinOccasion(ref, requestedVersion, occasion)
                    : items.pin(ref, requestedVersion);
        } catch (ItemCaptureException unavailable) {
            // Settled here, not in the listener: any other exception leaving this method rolls the transaction back.
            if (unavailable.code() != ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE) throw unavailable;
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "The occasion kept no record of the work as it was.",
                    ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE,
                    unavailable);
        }
        var now = clock.instant();
        Instant ordered = now;
        ItemEventName event = null;
        if (request.eventId() != null) {
            var fact = itemRows.event(request.workspaceId(), request.itemId(), request.eventId());
            if (!pinned.version().value().equals(fact.getItemVersion()))
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.REQUEST_CONFLICT, "The event version does not match the review.");
            event = new ItemEventName(fact.getName());
            ordered = fact.getOccurredAt();
        }
        List<Practice> selected;
        if (request.kind() == ReviewKind.SCAN) {
            if (!coverage.admitsRepositoryItem(workspace, ref.itemId()))
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "The repository is outside the review scope.");
            if (!ref.kind().value().equals("scm.repository"))
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "Select a repository for a scan.");
            selected = practices.findByWorkspaceId(request.workspaceId()).stream()
                    .filter(p -> {
                        var d = json.convertValue(p.getCurrentVersion().getDefinition(), PracticeDefinition.class);
                        return d.scan() != null && d.reviewedBy().by() == ReviewedByKind.HEPHAESTUS;
                    })
                    .filter(p -> request.practiceIds().isEmpty()
                            || request.practiceIds().contains(p.getId()))
                    .sorted(Comparator.comparing(Practice::getId))
                    .toList();
        } else {
            selected = switch (gate.evaluate(workspace, pinned, request.origin(), event, request.practiceIds())) {
                case GateDecision.Skip skip ->
                    throw new ReviewRequestRejected(
                            ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                            "No practice can review this item.",
                            skip.eventReason());
                case GateDecision.Run run -> run.matchedPractices();
            };
        }
        if (selected.isEmpty()
                || (!request.practiceIds().isEmpty()
                        && (selected.size() != request.practiceIds().size()
                                || !selected.stream()
                                        .map(Practice::getId)
                                        .toList()
                                        .containsAll(request.practiceIds()))))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "Select eligible practices from this workspace.");
        var planned = selected.stream()
                .map(p -> selection(workspace, p, request.kind(), pinned))
                .toList();
        var developers = new ArrayList<@Nullable Long>();
        for (var plan : planned) {
            if (plan.reason() == null)
                developers.add(plan.roles().size() == 1 ? plan.roles().getFirst() : null);
        }
        var models = bindings.models(request.workspaceId(), developers).stream()
                .sorted(Comparator.comparing(BoundModelSlot::slot))
                .toList();
        if (models.size() != 5
                || !models.stream()
                        .map(BoundModelSlot::slot)
                        .sorted()
                        .toList()
                        .equals(List.of("chat", "decision", "embedding", "reranking", "review")))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.MODEL_UNAVAILABLE,
                    "The review method must contain the five frozen slots.");
        if (!developers.isEmpty()
                && models.stream()
                                .filter(slot -> slot.slot().equals("review"))
                                .findFirst()
                                .orElseThrow()
                                .model()
                        == null)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.MODEL_UNAVAILABLE, "The review model is unavailable.");
        var digest = reviews.modelDigest(json.writeValueAsString(models));
        var method = new ReviewMethod(1, digest, models);
        ObjectNode checks = json.createObjectNode();
        checks.put("reuse", request.reuse());
        if (request.kind() == ReviewKind.SCAN) {
            var branch = pinned.state().get("defaultBranch");
            if (branch == null || branch.isBlank())
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.SOURCE_UNAVAILABLE,
                        "The repository default branch is unavailable.");
            checks.put("ref", branch);
        }
        var review = reviews.save(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(request.workspaceId())
                .itemId(request.itemId())
                .kind(request.kind())
                .itemVersion(pinned.version().value())
                .baseVersion(pinned.version().baseVersion())
                .origin(request.origin())
                .eventId(request.eventId())
                .batchId(request.batchId())
                .requestedById(request.requestedById())
                .budgetUsd(request.budgetUsd())
                .requestKey(request.requestKey())
                .requestDigest(request.requestDigest())
                .rolloutRevision(workspace.getReviewSettings().getRolloutRevision())
                .orderedAt(ordered)
                .createdAt(now)
                .checks(checks)
                .build());
        for (var plan : planned) {
            var practice = plan.practice();
            var version = plan.version();
            var assignment = assignments.save(ReviewPractice.builder()
                    .reviewId(review.getId())
                    .workspaceId(request.workspaceId())
                    .practiceId(practice.getId())
                    .practiceVersionId(version.getId())
                    .method(method)
                    .build());
            if (request.kind() == ReviewKind.ITEM) {
                var roles = plan.roles();
                var detail = json.createObjectNode();
                if (roles.size() == 1) detail.put("developerId", roles.getFirst());
                else detail.putNull("developerId");
                var reason = plan.reason();
                memberships.save(ReviewItem.builder()
                        .workspaceId(request.workspaceId())
                        .reviewId(review.getId())
                        .practiceId(practice.getId())
                        .itemId(request.itemId())
                        .itemVersion(pinned.version().value())
                        .state(reason == null ? ReviewItemState.LISTED : ReviewItemState.WITHHELD)
                        .reason(reason)
                        .detail(detail)
                        .build());
                assignment.setItemsListed(1);
                assignment.setListingComplete(true);
                if (reason != null) {
                    assignment.setStatus(ReviewPracticeStatus.WITHHELD);
                    assignment.setReason(reason);
                }
            }
        }
        if (request.kind() == ReviewKind.ITEM
                && memberships
                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                                request.workspaceId(), review.getId())
                        .stream()
                        .allMatch(m -> m.getState() == ReviewItemState.WITHHELD)) {
            review.setStatus(ReviewStatus.PARTIAL);
            review.setStopReason(ReviewStopReason.READINESS_REFUSED);
            review.setCompletedAt(now);
        } else events.publishEvent(new ReviewRequested(review.getId(), request.workspaceId()));
        return review.getId();
    }

    private static ReviewRequestRejected replayConflict(Review prior) {
        var refusal = new ReviewRequestRejected(
                ReviewRequestRejected.Reason.REQUEST_CONFLICT, "This request key has a different body.");
        if (prior.getKind() == ReviewKind.SCAN) refusal.getBody().setProperty("scanId", prior.getId());
        return refusal;
    }

    private SelectedPractice selection(Workspace workspace, Practice practice, ReviewKind kind, ItemSnapshot pinned) {
        var version = practice.getCurrentVersion();
        var definition = json.convertValue(version.getDefinition(), PracticeDefinition.class);
        if (kind == ReviewKind.SCAN) return new SelectedPractice(practice, version, List.of(), null);
        var roles = resolveRole(workspace.getId(), definition, pinned);
        String reason = definition.about() == null
                ? null
                : roles.isEmpty() ? "ROLE_UNAVAILABLE" : roles.size() > 1 ? "ROLE_AMBIGUOUS" : null;
        if (reason == null
                && !coverage.admits(
                        workspace,
                        coverage.repositoryItemId(
                                workspace.getId(), pinned.item().itemId()),
                        pinned.state().get("baseBranch"),
                        roles.size() == 1 ? roles.getFirst() : null,
                        pinned.item().kind().value().equals("scm.pull_request")
                                || pinned.item().kind().value().equals("scm.pull_request_review")))
            reason = "OUTSIDE_REVIEW_SCOPE";
        return new SelectedPractice(practice, version, roles, reason);
    }

    private record SelectedPractice(
            Practice practice,
            PracticeVersion version,
            List<Long> roles,
            @Nullable String reason) {}

    private List<Long> resolveRole(long workspaceId, PracticeDefinition definition, ItemSnapshot snapshot) {
        if (definition.about() == null) return List.of();
        var eligible = coverage.eligibleAccountIds(workspaceId);
        return snapshot.roles().stream()
                .filter(r -> r.human() && r.role() == definition.about())
                .map(r -> identities.resolveAccountId(
                        r.identity().providerId(),
                        r.identity().subject(),
                        r.identity().teamId()))
                .flatMap(Optional::stream)
                .filter(eligible::contains)
                .distinct()
                .sorted()
                .toList();
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
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus() != ReviewStatus.QUEUED
                || review.getChecks().path("cancellationRequested").asBoolean()) return false;
        review.setStatus(ReviewStatus.RUNNING);
        var checks = (ObjectNode) review.getChecks().deepCopy();
        checks.put("startedAt", clock.instant().toString());
        review.setChecks(checks);
        return true;
    }

    @Override
    @Transactional
    public void complete(long workspaceId, UUID reviewId, ReviewCompletion completion) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()) return;
        if (!completion.status().isTerminal())
            throw new IllegalArgumentException("Completion requires a terminal status.");
        if (hasUnsettledInvocation(workspaceId, reviewId))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT, "The active invocation must settle before closure.");
        boolean incomplete = (review.getKind() == ReviewKind.SCAN
                        && (review.getInventoryReceipt() == null
                                || !review.getInventoryReceipt()
                                        .path("complete")
                                        .asBoolean()
                                || assignments
                                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, reviewId)
                                        .stream()
                                        .anyMatch(p -> !Boolean.TRUE.equals(p.getListingComplete()))))
                || memberships.existsByWorkspaceIdAndReviewIdAndStateIn(
                        workspaceId, reviewId, List.of(ReviewItemState.WITHHELD, ReviewItemState.LISTED));
        boolean cancellationRequested =
                review.getChecks().path("cancellationRequested").asBoolean();
        var status = cancellationRequested
                ? ReviewStatus.CANCELLED
                : completion.status() == ReviewStatus.COMPLETE && incomplete
                        ? ReviewStatus.PARTIAL
                        : completion.status();
        review.setStatus(status);
        // A cancellation that nobody requested keeps the reason the runtime recorded, such as no sandbox room.
        review.setStopReason(
                status == ReviewStatus.COMPLETE
                        ? null
                        : status == ReviewStatus.CANCELLED
                                ? cancellationRequested || completion.reason() == null
                                        ? ReviewStopReason.CANCELLED
                                        : completion.reason()
                                : completion.reason() == null
                                        ? ReviewStopReason.EXECUTION_INCOMPLETE
                                        : completion.reason());
        review.setCompletedAt(completion.completedAt());
        for (var assignment : assignments.lockAssignments(workspaceId, reviewId)) {
            if (assignment.getStatus() == ReviewPracticeStatus.QUEUED) {
                // Capture never reached it, so it recorded nothing: it ends withheld for the reason the review stopped.
                assignment.setStatus(ReviewPracticeStatus.WITHHELD);
                assignment.setReason(
                        Objects.requireNonNullElse(review.getStopReason(), ReviewStopReason.EXECUTION_INCOMPLETE)
                                .name());
            } else if (assignment.getStatus() != ReviewPracticeStatus.WITHHELD
                    && assignment.getStatus() != ReviewPracticeStatus.FAILED) {
                assignment.setStatus(ReviewPracticeStatus.DONE);
            }
        }
        if (review.getKind() == ReviewKind.SCAN) reports.freeze(workspaceId, reviewId);
        if ((status == ReviewStatus.COMPLETE || status == ReviewStatus.PARTIAL)
                && memberships.existsByWorkspaceIdAndReviewIdAndStateInAndResultIdIsNotNull(
                        workspaceId, reviewId, List.of(ReviewItemState.JUDGED, ReviewItemState.REUSED)))
            events.publishEvent(new ReviewCompleted(reviewId, workspaceId));
    }

    @Override
    @Transactional
    public boolean cancel(long workspaceId, UUID reviewId, @Nullable String reason) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()) return false;
        if (review.getChecks().path("cancellationRequested").asBoolean()) return true;
        var checks = (ObjectNode) review.getChecks().deepCopy();
        checks.put("cancellationRequested", true);
        checks.put("cancellationReason", reason == null ? "CANCELLED_BY_ADMIN" : reason);
        review.setChecks(checks);
        events.publishEvent(
                new ReviewCancellationRequested(reviewId, workspaceId, reason == null ? "CANCELLED_BY_ADMIN" : reason));
        if (!hasUnsettledInvocation(workspaceId, reviewId)) {
            review.setStatus(ReviewStatus.CANCELLED);
            review.setStopReason(ReviewStopReason.CANCELLED);
            review.setCompletedAt(clock.instant());
            if (review.getKind() == ReviewKind.SCAN) reports.freeze(workspaceId, reviewId);
        }
        return true;
    }

    private boolean hasUnsettledInvocation(long workspaceId, UUID reviewId) {
        return !runtime.activeJobIds(workspaceId, reviewId).isEmpty()
                || !runtime.unsettledJobIds(workspaceId, reviewId).isEmpty();
    }

    private static void validate(ReviewRequest r) {
        if (r.workspaceId() <= 0
                || r.itemId() <= 0
                || r.practiceIds().stream().distinct().count()
                        != r.practiceIds().size())
            throw new IllegalArgumentException("Use positive item ids and distinct practices.");
        if ((r.origin() == ReviewOrigin.EVENT && r.eventId() == null)
                || (r.origin() == ReviewOrigin.REQUEST && r.requestedById() == null)
                || ((r.origin() == ReviewOrigin.SCHEDULE || r.origin() == ReviewOrigin.BACKFILL)
                        && r.batchId() == null))
            throw new IllegalArgumentException("The review origin requires its recorded link.");
        if (r.kind() == ReviewKind.SCAN
                && r.origin() == ReviewOrigin.REQUEST
                && (r.requestKey() == null || r.requestDigest() == null))
            throw new IllegalArgumentException("A scan requires its request key and digest.");
    }
}
