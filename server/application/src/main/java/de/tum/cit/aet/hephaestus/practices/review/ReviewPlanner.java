package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
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
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWithholdings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plans a review request: the practices it measures and those it withholds with their reasons, the frozen review
 * method, the checks the review records, and the sources the planned practices read. It also answers whether a review
 * still has an active or unsettled job, which planning asks of earlier reviews, and completing or cancelling a review
 * asks of that review.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewPlanner {
    private final ReviewRepository reviews;
    private final ReviewItemRepository memberships;
    private final PracticeRepository practices;
    private final PracticeVersionRepository practiceVersions;
    private final ProductItemQuery items;
    private final PracticeReviewCoverageService coverage;
    private final ReviewGate gate;
    private final AccountIdentityQuery identities;
    private final ReviewModelBindings bindings;
    private final ReviewRuntimeQuery runtime;
    private final ProductItemRepository itemRows;
    private final JsonMapper json;
    private final Clock clock;
    private final PracticeItemOptions itemOptions;
    private final ResultRepository results;
    private final List<CapturedApplicability> applicability;
    private final ItemEventService itemEvents;
    private final OffPracticeMilestones offMilestones;

    /** @param gated every practice that the gate selects for an item review, before its results and readiness */
    record Plan(
            @Nullable Instant occurredAt,
            List<Practice> gated,
            List<SelectedPractice> planned,
            ReviewMethod method,
            ObjectNode checks) {}

    Plan plan(
            ReviewRequest request,
            ReviewWorkspace workspace,
            ItemRef ref,
            ItemSnapshot pinned,
            Map<UUID, Currentness> currentness) {
        var occasion = ReviewServiceImpl.occasion(request);
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
        List<Practice> gated = List.of();
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
        } else if (request.origin() == ReviewOrigin.TRIAL) {
            // Loaded by its id, as a draft practice is loaded nowhere else; the trial tries the version it names.
            var practice = practices
                    .findById(request.practiceIds().getFirst())
                    .orElseThrow(() -> new ReviewRequestRejected(
                            ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "Select a practice of this workspace."));
            selected = switch (gate.trial(workspace, pinned, practice, version(request, practice))) {
                case GateDecision.Skip skip ->
                    throw new ReviewRequestRejected(
                            ReviewRequestRejected.Reason.ITEM_INELIGIBLE, skip.reason(), skip.eventReason());
                case GateDecision.Run run -> run.matchedPractices();
            };
            gated = selected;
        } else {
            boolean event = request.origin() == ReviewOrigin.EVENT;
            Set<Long> carried = event
                    ? Set.copyOf(memberships.lastWithheldPracticeIds(
                            request.workspaceId(), request.itemId(), ReviewWithholdings.CARRIED))
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
            gated = selected;
            if (event) selected = pending(pinned, occasions, selected, currentness);
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
                        && selected.stream()
                                .anyMatch(p -> needsChecks(definition(version(request, p)), checksOfItem.source()))
                ? checksOfItem.checks(pinned, occasion)
                : ItemChecks.SETTLED;
        // Read once for every practice: the review scope, the eligible members, the item's repository and its people.
        var itemCoverage = request.kind() == ReviewKind.ITEM ? itemCoverage(workspace, pinned) : null;
        var planned = selected.stream()
                .map(p -> selection(
                        p, version(request, p), request.kind(), pinned, itemCoverage, checksSource, headChecks))
                .toList();
        refuseWorkThatNoPracticeReviews(planned);
        // A trial with nothing to try would end at once; it is refused instead, with the reason it could not run.
        if (request.origin() == ReviewOrigin.TRIAL && planned.getFirst().reason() != null) {
            var refusal = new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "Hephaestus cannot try this practice on this work now.");
            refusal.getBody().setProperty("withheldReason", planned.getFirst().reason());
            throw refusal;
        }
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
            checks.putObject(ReviewServiceImpl.COMMENT_ANSWER)
                    .put("kind", answer.kind().name())
                    .put("address", answer.address());
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
        return new Plan(occurredAt, gated, planned, method, checks);
    }

    /**
     * Of the practices the occasions select, those to measure now. Occasions that all continue leave out a practice in
     * feedback mode Off that reviews at a milestone: it waits for that milestone and never follows a newer head. One
     * that this version still owes, such as for its checks or a spent deadline, finishes here. A practice that an
     * earlier review of the item still measures (queued, or running with an unsettled job) holds the whole window until
     * that review ends, so its occasions are never lost. An event review that never started holds a window of
     * occasions that join it whatever it lists, so that its claim replaces it instead of starting a sandbox on work
     * that moved. A continuing occasion leaves out every practice whose result is current; a milestone measures them
     * again.
     */
    private List<Practice> pending(
            ItemSnapshot pinned,
            Set<ItemEventName> occasions,
            List<Practice> gated,
            Map<UUID, Currentness> currentness) {
        long workspaceId = pinned.item().workspaceId();
        long itemId = pinned.item().itemId();
        var kind = pinned.item().kind();
        var waiting = new HashSet<Long>();
        if (offMilestones.continuing(kind, occasions)) {
            waiting.addAll(offMilestones.waitingForAMilestone(workspaceId, kind, gated));
            waiting.removeAll(memberships.owedAtVersion(
                    workspaceId, itemId, pinned.version().value(), ReviewWithholdings.CARRIED));
        }
        var selected = gated.stream().filter(p -> !waiting.contains(p.getId())).toList();
        var selectedIds = selected.stream().map(Practice::getId).collect(Collectors.toSet());
        // Starting a review commits before its job is enqueued, so a running review with no job yet still measures.
        // One that never got a job stops holding the window one cap after it started.
        Instant enqueuedBy = clock.instant().minus(itemEvents.quietWindowCap());
        // An event review that never started holds a window whatever it lists: its claim replaces it while this
        // occasion waits, so no sandbox starts on work that moved. Only an occasion that joins a window can replace it.
        boolean replaceable = occasions.stream()
                .allMatch(name -> itemOptions.occasion(kind, name).joinsWindow());
        boolean measured = memberships.listedPractices(workspaceId, itemId).stream()
                .anyMatch(listed -> (replaceable
                                && listed.getStatus().equals(ReviewStatus.QUEUED.name())
                                && listed.getStartedAt() == null
                                && listed.getOrigin().equals(ReviewOrigin.EVENT.name()))
                        || (selectedIds.contains(listed.getPracticeId())
                                && (listed.getStatus().equals(ReviewStatus.QUEUED.name())
                                        || hasUnsettledInvocation(workspaceId, listed.getReviewId())
                                        || listed.getStartedAt() == null
                                        || listed.getStartedAt().isAfter(enqueuedBy))));
        if (measured)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT,
                    "An earlier review of this item still measures a selected practice.",
                    ReviewRequestReason.REVIEW_IN_PROGRESS);
        if (selected.isEmpty() && !waiting.isEmpty())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "Every selected practice has feedback mode Off and waits for a milestone of the work.",
                    ReviewRequestReason.FEEDBACK_MODE_OFF);
        if (occasions.stream().anyMatch(name -> itemOptions.occasion(kind, name) == OccasionKind.MILESTONE))
            return selected;
        var current = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId).stream()
                .filter(result -> currentness.get(result.getId()) == Currentness.CURRENT)
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

    private PracticeDefinition definition(PracticeVersion version) {
        return json.convertValue(version.getDefinition(), PracticeDefinition.class);
    }

    /** The version that the request measures: the current one, or for a trial the version it names. */
    PracticeVersion version(ReviewRequest request, Practice practice) {
        var id = request.practiceVersionId();
        if (id == null) return practice.getCurrentVersion();
        return practiceVersions
                .findByIdAndWorkspaceId(id, request.workspaceId())
                .filter(version -> Objects.equals(version.getPractice().getId(), practice.getId()))
                .orElseThrow(() -> new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.ITEM_INELIGIBLE, "Select a version of this practice."));
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
            case PENDING -> ReviewWithholdings.CHECKS_PENDING;
            case AWAITING_APPROVAL -> ReviewWithholdings.CHECKS_AWAITING_APPROVAL;
            case NONE_REPORTED -> ReviewWithholdings.NO_CHECKS_REPORTED;
        };
    }

    private SelectedPractice selection(
            Practice practice,
            PracticeVersion version,
            ReviewKind kind,
            ItemSnapshot pinned,
            @Nullable ItemCoverage itemCoverage,
            @Nullable SourceKind checksSource,
            ItemChecks checks) {
        var definition = definition(version);
        if (kind == ReviewKind.SCAN) return new SelectedPractice(practice, version, List.of(), null);
        var covered = Objects.requireNonNull(itemCoverage);
        var roles = definition.about() == null
                ? List.<Long>of()
                : covered.accounts().getOrDefault(definition.about(), List.of());
        String reason = definition.about() == null
                ? null
                : roles.isEmpty()
                        ? missingRoleReason(definition.about(), pinned)
                        : roles.size() > 1 ? ReviewWithholdings.ROLE_AMBIGUOUS : null;
        if (reason == null
                && !covered.admission()
                        .admits(
                                covered.repository(),
                                pinned.state().get("baseBranch"),
                                roles.size() == 1 ? roles.getFirst() : null,
                                pinned.item().kind().value().equals("scm.pull_request")
                                        || pinned.item().kind().value().equals("scm.pull_request_review")))
            reason = ReviewWithholdings.OUTSIDE_REVIEW_SCOPE;
        if (reason == null) reason = readiness(definition, checksSource, checks);
        return new SelectedPractice(practice, version, roles, reason);
    }

    /**
     * Selection that leaves no practice to run and none to carry to a later edge starts no review. When a bot holds the
     * role or the work is out of scope, the event or the request is refused with that reason, so the work does not read
     * as not ready. Withholdings that a later edge lifts, such as pending checks, still record a review, whose withheld
     * practices that edge carries.
     */
    private static void refuseWorkThatNoPracticeReviews(List<SelectedPractice> planned) {
        var reasons = new HashSet<@Nullable String>();
        planned.forEach(plan -> reasons.add(plan.reason()));
        if (reasons.contains(null) || reasons.stream().anyMatch(ReviewWithholdings.CARRIED::contains)) return;
        ReviewRequestReason refusal;
        if (reasons.contains(ReviewWithholdings.BOT_REVIEWER)) refusal = ReviewRequestReason.BOT_REVIEWER;
        else if (reasons.contains(ReviewWithholdings.BOT_AUTHOR)) refusal = ReviewRequestReason.BOT_AUTHOR;
        else if (reasons.contains(ReviewWithholdings.OUTSIDE_REVIEW_SCOPE))
            refusal = ReviewRequestReason.OUT_OF_REVIEW_SCOPE;
        else return;
        throw new ReviewRequestRejected(
                ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                refusal == ReviewRequestReason.OUT_OF_REVIEW_SCOPE
                        ? "This work or this person is outside the workspace’s review scope."
                        : "Hephaestus does not review work by bots.",
                refusal);
    }

    private static String missingRoleReason(AboutRole role, ItemSnapshot item) {
        boolean authoredWork = role == AboutRole.AUTHOR
                || (role == AboutRole.REVIEWER && item.item().kind().value().equals("scm.pull_request_review"));
        var holders =
                item.roles().stream().filter(holder -> holder.role() == role).toList();
        return authoredWork && !holders.isEmpty() && holders.stream().allMatch(RoleHolder::bot)
                ? role == AboutRole.REVIEWER ? ReviewWithholdings.BOT_REVIEWER : ReviewWithholdings.BOT_AUTHOR
                : ReviewWithholdings.ROLE_UNAVAILABLE;
    }

    Set<SourceKind> queuedKinds(ItemSnapshot item, List<SelectedPractice> plans) {
        return readKinds(
                item,
                plans.stream()
                        .filter(plan -> plan.reason() == null)
                        .map(SelectedPractice::version)
                        .toList());
    }

    /** The sources that these practice versions read, and those that their applicability conditions read. */
    Set<SourceKind> readKinds(ItemSnapshot item, List<PracticeVersion> versions) {
        var kinds = new HashSet<SourceKind>();
        for (var version : versions) {
            var definition = json.treeToValue(version.getDefinition(), PracticeDefinition.class);
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

    JsonNode queuedSources(Set<SourceKind> kinds, Map<SourceKind, String> fingerprints) {
        var facts = json.createObjectNode();
        var queued = facts.putObject(ReviewCaptureDecision.SOURCE_FINGERPRINTS);
        fingerprints.forEach((kind, value) -> {
            if (kinds.contains(kind)) queued.put(kind.value(), value);
        });
        return facts;
    }

    record SelectedPractice(
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

    boolean hasUnsettledInvocation(long workspaceId, UUID reviewId) {
        return !runtime.activeJobIds(workspaceId, reviewId).isEmpty()
                || !runtime.unsettledJobIds(workspaceId, reviewId).isEmpty();
    }
}
