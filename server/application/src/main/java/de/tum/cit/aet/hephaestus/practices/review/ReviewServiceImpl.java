package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecks;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCancellationRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    /**
     * Membership reasons of a practice that was never measured and is still owed: it waits for the item's checks, or a
     * newer occasion replaced its review. The next edge selects it again, whatever that edge's occasions select.
     */
    private static final List<String> CARRIED_REASONS = List.of(
            "CHECKS_PENDING", "NO_CHECKS_REPORTED", "CHECKS_AWAITING_APPROVAL", ReviewStopReason.REPLACED.name());

    /** Where a review that a comment requested answers that comment when no feedback does. */
    static final String COMMENT_ANSWER = "commentAnswer";

    private final ReviewRepository reviews;
    private final ReviewStart starts;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository memberships;
    private final PracticeRepository practices;
    private final ReviewSnapshotQueryService snapshots;
    private final WorkspaceQueries workspaces;
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
    private final PracticeItemOptions itemOptions;
    private final ResultRepository results;
    private final ResultFreshness freshness;
    private final List<CapturedApplicability> applicability;
    private final ItemEventService itemEvents;
    private final ReviewClosure closure;
    private final ReviewRequestTransactions transactions;

    /**
     * Admits the request without a lock. Then it pins the item and reads the sources that the plan queues with no
     * transaction, since a repository pin or a source read fetches the repository. It records the review under the
     * workspace lock, which admits and plans the request again. A replay returns its review without a pin. A caller
     * that already holds a transaction gains nothing from the early read, so the locked step reads its sources.
     */
    @Override
    public UUID request(ReviewRequest request) {
        validate(request);
        return switch (transactions.read(() -> admit(request, workspace(request)))) {
            case Admission.Replay replay -> replay.reviewId();
            case Admission.Admitted admitted -> {
                var pinned = pin(request, admitted.item());
                var early = TransactionSynchronizationManager.isActualTransactionActive()
                        ? null
                        : earlyRead(request, admitted.item(), pinned);
                yield transactions.write(() -> {
                    var workspace =
                            workspaces.findByIdForUpdate(request.workspaceId()).orElseThrow();
                    return switch (admit(request, workspace)) {
                        case Admission.Replay replay -> replay.reviewId();
                        case Admission.Admitted locked -> {
                            requirePinnedVersion(request, pinned);
                            yield recordReview(
                                    request, workspace, pinned, plan(request, workspace, locked.item(), pinned), early);
                        }
                    };
                });
            }
        };
    }

    /**
     * The pin ran before the lock, so a newer version of the work may have arrived since, and its event review may
     * already measure it. Under the lock the stored version must still be the pinned one. An event review pins its
     * occasion's version, which {@link #plan} checks against the occasion.
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

    private sealed interface Admission {
        record Replay(UUID reviewId) implements Admission {}

        record Admitted(ItemRef item) implements Admission {}
    }

    private Admission admit(ReviewRequest request, ReviewWorkspace workspace) {
        if (request.requestKey() != null) {
            var existing = reviews.findByWorkspaceIdAndRequestKey(request.workspaceId(), request.requestKey());
            if (existing.isPresent()) {
                if (!Objects.equals(existing.orElseThrow().getRequestDigest(), request.requestDigest()))
                    throw replayConflict(existing.orElseThrow());
                return new Admission.Replay(existing.orElseThrow().getId());
            }
        }
        if (request.eventId() != null) {
            var prior = reviews.findByWorkspaceIdAndEventId(request.workspaceId(), request.eventId());
            if (prior.isPresent())
                return new Admission.Replay(prior.orElseThrow().getId());
        }
        if (!workspace.active())
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
        return new Admission.Admitted(items.require(request.workspaceId(), request.itemId()));
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
            // Settled here, not in the listener: any other exception leaving the request rolls its transaction back.
            if (unavailable.code() != ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE) throw unavailable;
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "The occasion kept no record of the work as it was.",
                    ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE,
                    unavailable);
        }
    }

    /** The sources that a first plan queues for an item review, and their fingerprints, read with no transaction. */
    private record EarlyRead(Set<SourceKind> kinds, Map<SourceKind, String> fingerprints) {}

    private @Nullable EarlyRead earlyRead(ReviewRequest request, ItemRef ref, ItemSnapshot pinned) {
        if (request.kind() != ReviewKind.ITEM) return null;
        var kinds = transactions.read(() -> queuedKinds(
                pinned, plan(request, workspace(request), ref, pinned).planned()));
        return new EarlyRead(kinds, items.fingerprints(pinned.item(), kinds, occasion(request)));
    }

    /**
     * The fingerprints of the sources that the locked plan queues: the early read's, or read now in the caller's
     * transaction. A practice that came to read another source after the early read refuses the request.
     */
    private Map<SourceKind, String> fingerprints(
            ItemSnapshot pinned, ReviewRequest request, Set<SourceKind> kinds, @Nullable EarlyRead early) {
        if (early == null) return items.fingerprints(pinned.item(), kinds, occasion(request));
        if (!early.kinds().containsAll(kinds))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT,
                    "The practices changed what they read while the review was requested.");
        return early.fingerprints();
    }

    private static @Nullable UUID occasion(ReviewRequest request) {
        return request.origin() == ReviewOrigin.EVENT ? request.eventId() : null;
    }

    private record Plan(
            @Nullable Instant occurredAt, List<SelectedPractice> planned, ReviewMethod method, ObjectNode checks) {}

    private Plan plan(ReviewRequest request, ReviewWorkspace workspace, ItemRef ref, ItemSnapshot pinned) {
        var occasion = occasion(request);
        Instant occurredAt = null;
        var occasions = new HashSet<>(request.occasions());
        if (request.eventId() != null) {
            var fact = itemRows.event(request.workspaceId(), request.itemId(), request.eventId());
            if (!pinned.version().value().equals(fact.getItemVersion()))
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.REQUEST_CONFLICT, "The event version does not match the review.");
            occasions.add(new ItemEventName(fact.getName()));
            occurredAt = fact.getOccurredAt();
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
            boolean event = request.origin() == ReviewOrigin.EVENT;
            Set<Long> carried = event
                    ? Set.copyOf(memberships.lastWithheldPracticeIds(
                            request.workspaceId(), request.itemId(), CARRIED_REASONS))
                    : Set.of();
            selected = switch (gate.evaluate(
                    workspace, pinned, request.origin(), occasions, carried, request.practiceIds())) {
                case GateDecision.Skip skip ->
                    throw new ReviewRequestRejected(
                            ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                            "No practice can review this item.",
                            skip.eventReason());
                case GateDecision.Run run -> run.matchedPractices();
            };
            if (event) selected = pending(pinned, occasions, selected);
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
        var checksOfItem =
                request.kind() == ReviewKind.ITEM ? items.checks(ref.kind()).orElse(null) : null;
        var checksSource = checksOfItem == null ? null : checksOfItem.source();
        // Read once, and only when a selected practice needs the checks settled: the checks its capture will read.
        ItemChecks headChecks = checksOfItem != null
                        && selected.stream().anyMatch(p -> needsChecks(definition(p), checksOfItem.source()))
                ? checksOfItem.checks(pinned, occasion)
                : ItemChecks.SETTLED;
        // Read once for every practice: the review scope, the eligible members, the item's repository and its people.
        var itemCoverage = request.kind() == ReviewKind.ITEM ? itemCoverage(workspace, pinned) : null;
        var planned = selected.stream()
                .map(p -> selection(p, request.kind(), pinned, itemCoverage, checksSource, headChecks))
                .toList();
        var developers = new ArrayList<@Nullable Long>();
        for (var plan : planned) {
            if (plan.reason() == null)
                developers.add(plan.roles().size() == 1 ? plan.roles().getFirst() : null);
        }
        var models = bindings.models(request.workspaceId(), developers);
        if (models.size() != 1 || !models.getFirst().slot().equals("review"))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.MODEL_UNAVAILABLE,
                    "The review method must contain the one frozen review slot.");
        if (!developers.isEmpty() && models.getFirst().model() == null)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.MODEL_UNAVAILABLE, "The review model is unavailable.");
        var digest = reviews.modelDigest(json.writeValueAsString(models));
        var method = new ReviewMethod(1, digest, models);
        ObjectNode checks = json.createObjectNode();
        checks.put("reuse", request.reuse());
        var answer = request.commentAnswer();
        if (answer != null)
            checks.putObject(COMMENT_ANSWER).put("kind", answer.kind().name()).put("address", answer.address());
        if (request.kind() == ReviewKind.SCAN) {
            var branch = pinned.state().get("defaultBranch");
            if (branch == null || branch.isBlank())
                throw new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.SOURCE_UNAVAILABLE,
                        "The repository default branch is unavailable.");
            checks.put("ref", branch);
            if (request.scanRef() != null && !request.scanRef().equals(branch)) {
                var refusal = new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.REF_UNSUPPORTED, "Only the default branch supports scans.");
                refusal.getBody().setProperty("supportedRef", branch);
                throw refusal;
            }
            if (request.expectedVersion() != null
                    && !request.expectedVersion().equals(pinned.version().value())) {
                var refusal = new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.VERSION_CHANGED, "The repository version changed.");
                refusal.getBody().setProperty("currentVersion", pinned.version().value());
                throw refusal;
            }
        }
        return new Plan(occurredAt, planned, method, checks);
    }

    private UUID recordReview(
            ReviewRequest request,
            ReviewWorkspace workspace,
            ItemSnapshot pinned,
            Plan plan,
            @Nullable EarlyRead early) {
        var now = clock.instant();
        var ordered = plan.occurredAt() != null ? plan.occurredAt() : now;
        var planned = plan.planned();
        // Before the save: a refusal commits what came before it.
        JsonNode queuedSources = null;
        if (request.kind() == ReviewKind.ITEM) {
            var kinds = queuedKinds(pinned, planned);
            queuedSources = queuedSources(kinds, fingerprints(pinned, request, kinds, early));
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
                .rolloutRevision(workspace.reviewSettings().rolloutRevision())
                .orderedAt(ordered)
                .createdAt(now)
                .checks(plan.checks())
                .build());
        for (var selected : planned) {
            var practice = selected.practice();
            var version = selected.version();
            var assignment = assignments.save(ReviewPractice.builder()
                    .reviewId(review.getId())
                    .workspaceId(request.workspaceId())
                    .practiceId(practice.getId())
                    .practiceVersionId(version.getId())
                    .captureDecision(selected.reason() == null ? queuedSources : null)
                    .method(plan.method())
                    .build());
            if (request.kind() == ReviewKind.ITEM) {
                var roles = selected.roles();
                var detail = json.createObjectNode();
                if (roles.size() == 1) detail.put("developerId", roles.getFirst());
                else detail.putNull("developerId");
                var reason = selected.reason();
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
            closure.close(review, ReviewStatus.PARTIAL, ReviewStopReason.READINESS_REFUSED, now);
        } else events.publishEvent(new ReviewRequested(review.getId(), request.workspaceId()));
        return review.getId();
    }

    private static ReviewRequestRejected replayConflict(Review prior) {
        var refusal = new ReviewRequestRejected(
                ReviewRequestRejected.Reason.REQUEST_CONFLICT, "This request key has a different body.");
        if (prior.getKind() == ReviewKind.SCAN) refusal.getBody().setProperty("scanId", prior.getId());
        return refusal;
    }

    /**
     * Of the practices the occasions select, those to measure now. A practice that an earlier review of the item still
     * measures (queued, or running with an unsettled job) holds the whole window until that review ends, so its
     * occasions are never lost. A continuing occasion
     * leaves out every practice whose result is current; a milestone measures them again.
     */
    private List<Practice> pending(ItemSnapshot pinned, Set<ItemEventName> occasions, List<Practice> selected) {
        long workspaceId = pinned.item().workspaceId();
        long itemId = pinned.item().itemId();
        var selectedIds = selected.stream().map(Practice::getId).collect(Collectors.toSet());
        // Starting a review commits before its job is enqueued, so a running review with no job yet still measures.
        // One that never got a job stops holding the window one cap after it started.
        Instant enqueuedBy = clock.instant().minus(itemEvents.quietWindowCap());
        boolean measured = memberships.listedPractices(workspaceId, itemId).stream()
                .filter(listed -> selectedIds.contains(listed.getPracticeId()))
                .anyMatch(listed -> listed.getStatus().equals(ReviewStatus.QUEUED.name())
                        || hasUnsettledInvocation(workspaceId, listed.getReviewId())
                        || listed.getStartedAt() == null
                        || listed.getStartedAt().isAfter(enqueuedBy));
        if (measured)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT,
                    "An earlier review of this item still measures a selected practice.",
                    ReviewRequestReason.REVIEW_IN_PROGRESS);
        if (occasions.stream()
                .anyMatch(name -> itemOptions.occasion(pinned.item().kind(), name) == OccasionKind.MILESTONE))
            return selected;
        var claims = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId);
        var states = freshness.states(workspaceId, claims);
        var current = claims.stream()
                .filter(result -> states.get(result.getId()) == Currentness.CURRENT)
                .map(Result::getPracticeVersionId)
                .collect(Collectors.toSet());
        var pending = selected.stream()
                .filter(p -> !current.contains(p.getCurrentVersion().getId()))
                .toList();
        if (pending.isEmpty())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "Every selected practice already has a current result.",
                    ReviewRequestReason.ALREADY_CURRENT);
        return pending;
    }

    private PracticeDefinition definition(Practice practice) {
        return json.convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class);
    }

    /** An optional read of the checks never waits: the practice judges without them, as it would with none. */
    private static boolean needsChecks(PracticeDefinition definition, SourceKind checksSource) {
        return definition.reads().sources().stream()
                .anyMatch(read -> read.source().equals(checksSource) && read.need() != ReadNeed.OPTIONAL);
    }

    private static @Nullable String readiness(
            PracticeDefinition definition, @Nullable SourceKind checksSource, ItemChecks checks) {
        if (checksSource == null || !needsChecks(definition, checksSource)) return null;
        return switch (checks) {
            case SETTLED -> null;
            case PENDING -> "CHECKS_PENDING";
            case AWAITING_APPROVAL -> "CHECKS_AWAITING_APPROVAL";
            case NONE_REPORTED -> "NO_CHECKS_REPORTED";
        };
    }

    private SelectedPractice selection(
            Practice practice,
            ReviewKind kind,
            ItemSnapshot pinned,
            @Nullable ItemCoverage itemCoverage,
            @Nullable SourceKind checksSource,
            ItemChecks checks) {
        var version = practice.getCurrentVersion();
        var definition = json.convertValue(version.getDefinition(), PracticeDefinition.class);
        if (kind == ReviewKind.SCAN) return new SelectedPractice(practice, version, List.of(), null);
        var covered = Objects.requireNonNull(itemCoverage);
        var roles = definition.about() == null
                ? List.<Long>of()
                : covered.accounts().getOrDefault(definition.about(), List.of());
        String reason = definition.about() == null
                ? null
                : roles.isEmpty()
                        ? missingRoleReason(definition.about(), pinned)
                        : roles.size() > 1 ? "ROLE_AMBIGUOUS" : null;
        if (reason == null
                && !covered.admission()
                        .admits(
                                covered.repository(),
                                pinned.state().get("baseBranch"),
                                roles.size() == 1 ? roles.getFirst() : null,
                                pinned.item().kind().value().equals("scm.pull_request")
                                        || pinned.item().kind().value().equals("scm.pull_request_review")))
            reason = "OUTSIDE_REVIEW_SCOPE";
        if (reason == null) reason = readiness(definition, checksSource, checks);
        return new SelectedPractice(practice, version, roles, reason);
    }

    private static String missingRoleReason(AboutRole role, ItemSnapshot item) {
        boolean authoredWork = role == AboutRole.AUTHOR
                || (role == AboutRole.REVIEWER && item.item().kind().value().equals("scm.pull_request_review"));
        var holders =
                item.roles().stream().filter(holder -> holder.role() == role).toList();
        return authoredWork && !holders.isEmpty() && holders.stream().allMatch(RoleHolder::bot)
                ? role == AboutRole.REVIEWER ? "BOT_REVIEWER" : "BOT_AUTHOR"
                : "ROLE_UNAVAILABLE";
    }

    private Set<SourceKind> queuedKinds(ItemSnapshot item, List<SelectedPractice> plans) {
        var kinds = new HashSet<SourceKind>();
        for (var plan : plans) {
            if (plan.reason() != null) continue;
            var definition = json.treeToValue(plan.version().getDefinition(), PracticeDefinition.class);
            definition.reads().sources().forEach(read -> kinds.add(read.source()));
            var conditions = definition.appliesWhen();
            if (conditions == null) continue;
            var clauses = conditions.anyOf().stream()
                    .map(condition -> new CapturedApplicability.Condition(
                            condition.changedPath(),
                            condition.diffContains(),
                            condition.hasItems(),
                            Boolean.TRUE.equals(condition.merged())))
                    .toList();
            for (var adapter : applicability)
                adapter.evaluate(item.item().kind(), clauses, List.of(), Map.of())
                        .forEach(clause -> kinds.add(clause.readFrom()));
        }
        return Set.copyOf(kinds);
    }

    private JsonNode queuedSources(Set<SourceKind> kinds, Map<SourceKind, String> fingerprints) {
        var facts = json.createObjectNode();
        var queued = facts.putObject(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
        fingerprints.forEach((kind, value) -> {
            if (kinds.contains(kind)) queued.put(kind.value(), value);
        });
        return facts;
    }

    private record SelectedPractice(
            Practice practice,
            PracticeVersion version,
            List<Long> roles,
            @Nullable String reason) {}

    /** What every practice of one item review is checked against: who holds each role, and the review scope. */
    private record ItemCoverage(
            PracticeReviewCoverageService.Admission admission,
            @Nullable Long repository,
            Map<AboutRole, List<Long>> accounts) {}

    private ItemCoverage itemCoverage(ReviewWorkspace workspace, ItemSnapshot pinned) {
        var admission = coverage.admission(workspace);
        var accounts = new EnumMap<AboutRole, List<Long>>(AboutRole.class);
        for (var role : AboutRole.values())
            accounts.put(
                    role,
                    pinned.roles().stream()
                            .filter(r -> r.human() && r.role() == role)
                            .map(r -> identities.resolveAccountId(
                                    r.identity().providerId(),
                                    r.identity().subject(),
                                    r.identity().teamId()))
                            .flatMap(Optional::stream)
                            .filter(admission.eligible()::contains)
                            .distinct()
                            .sorted()
                            .toList());
        return new ItemCoverage(
                admission,
                coverage.repositoryItemId(workspace.id(), pinned.item().itemId()),
                accounts);
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
                        workspaceId,
                        reviewId,
                        List.of(ReviewItemState.WITHHELD, ReviewItemState.LISTED, ReviewItemState.STAGED));
        boolean cancellationRequested = review.isCancelRequested();
        var status = cancellationRequested
                ? ReviewStatus.CANCELLED
                : completion.status() == ReviewStatus.COMPLETE && incomplete
                        ? ReviewStatus.PARTIAL
                        : completion.status();
        // A cancellation that nobody requested keeps the runtime's recorded reason.
        var reason = status == ReviewStatus.COMPLETE
                ? null
                : status == ReviewStatus.CANCELLED
                        ? cancellationRequested || completion.reason() == null
                                ? ReviewStopReason.CANCELLED
                                : completion.reason()
                        : completion.reason() == null ? unmeasuredReason(review) : completion.reason();
        closure.close(review, status, reason, completion.completedAt());
    }

    @Override
    @Transactional
    public boolean cancel(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()) return false;
        if (!review.requestCancel()) return true;
        events.publishEvent(new ReviewCancellationRequested(reviewId, workspaceId));
        if (!hasUnsettledInvocation(workspaceId, reviewId))
            closure.close(review, ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED, clock.instant());
        return true;
    }

    /**
     * Why a review whose run ended without a reason of its own left practices unmeasured: the one reason that all of
     * them share, when Hephaestus withheld each on purpose. A practice whose work changed after the capture is
     * {@code REPLACED}, and one that waits for the item's checks or its verification is not ready. A practice the run
     * left open, or a mix of reasons, is execution that did not finish.
     */
    private ReviewStopReason unmeasuredReason(Review review) {
        if (review.getKind() != ReviewKind.ITEM) return ReviewStopReason.EXECUTION_INCOMPLETE;
        var reasons = new HashSet<ReviewStopReason>();
        for (var member : memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                review.getWorkspaceId(), review.getId())) {
            if (member.getState() == ReviewItemState.LISTED || member.getState() == ReviewItemState.STAGED)
                return ReviewStopReason.EXECUTION_INCOMPLETE;
            if (member.getState() == ReviewItemState.WITHHELD) reasons.add(owed(member.getReason()));
        }
        return reasons.size() == 1 ? reasons.iterator().next() : ReviewStopReason.EXECUTION_INCOMPLETE;
    }

    private static ReviewStopReason owed(@Nullable String reason) {
        if (ReviewStopReason.REPLACED.name().equals(reason)) return ReviewStopReason.REPLACED;
        return CARRIED_REASONS.contains(reason)
                        || ReviewStopReason.READINESS_REFUSED.name().equals(reason)
                ? ReviewStopReason.READINESS_REFUSED
                : ReviewStopReason.EXECUTION_INCOMPLETE;
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
