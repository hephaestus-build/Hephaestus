package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackGate {
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final FeedbackDecisionRepository decisions;
    private final ResultRepository results;
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final ReviewRepository reviews;
    private final FeedbackModeQuery feedbackModes;
    private final EvidenceAuthorization evidence;
    private final PersonProcessingSuppression suppression;
    private final SilentModeQuery silentMode;
    private final List<ItemSource> sources;
    private final Clock clock;
    private final FeedbackContentDigest digests;

    @Transactional(readOnly = true)
    public FeedbackCheck evaluate(Feedback feedback, FeedbackCheckStage stage, boolean checkApproval) {
        long workspaceId = feedback.getWorkspaceId();
        UUID feedbackId = feedback.getId();
        var edges = basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspaceId, feedbackId);
        var support = results.findByWorkspaceIdAndIdIn(
                workspaceId,
                edges.stream().map(FeedbackBasis::getResultId).distinct().toList());
        return evaluate(
                feedback,
                stage,
                checkApproval,
                support,
                edges.stream().map(FeedbackBasis::getResultId).distinct().count(),
                basis.hasIncorrectBasis(workspaceId, feedbackId),
                copies.hasWriteFence(workspaceId, feedbackId),
                new GateReads(workspaceId));
    }

    /**
     * {@link #evaluate} for the pieces of one workspace, keyed by feedback id in their order. Every piece gets the
     * answer it gets on its own; the pieces' bases, results, reviews, evidence and feedback modes load once for all of
     * them, and pieces that share a recipient, a practice or a work item share its lookups.
     */
    @Transactional(readOnly = true)
    public Map<UUID, FeedbackCheck> evaluateAll(
            long workspaceId, Collection<Feedback> pieces, FeedbackCheckStage stage, boolean checkApproval) {
        var owned = pieces.stream()
                .filter(piece -> piece.getWorkspaceId() == workspaceId)
                .toList();
        if (owned.isEmpty()) return Map.of();
        var ids = owned.stream().map(Feedback::getId).toList();
        Map<UUID, List<UUID>> resultIds = new HashMap<>();
        basis.findByWorkspaceIdAndFeedbackIdInOrderByOrdinalAsc(workspaceId, ids)
                .forEach(edge -> resultIds
                        .computeIfAbsent(edge.getFeedbackId(), ignored -> new ArrayList<>())
                        .add(edge.getResultId()));
        var rowById = results
                .findByWorkspaceIdAndIdIn(
                        workspaceId,
                        resultIds.values().stream()
                                .flatMap(List::stream)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Result::getId, Function.identity()));
        var incorrect = Set.copyOf(basis.incorrectBasisFeedbackIds(workspaceId, ids));
        var fenced = Set.copyOf(copies.writeFencedFeedbackIds(workspaceId, ids));
        var reads = new GateReads(workspaceId);
        reads.prefetch(owned, rowById.values());
        Map<UUID, FeedbackCheck> checks = new LinkedHashMap<>();
        for (var piece : owned) {
            var distinct = resultIds.getOrDefault(piece.getId(), List.of()).stream()
                    .distinct()
                    .toList();
            var support =
                    distinct.stream().map(rowById::get).filter(Objects::nonNull).toList();
            checks.put(
                    piece.getId(),
                    evaluate(
                            piece,
                            stage,
                            checkApproval,
                            support,
                            distinct.size(),
                            incorrect.contains(piece.getId()),
                            fenced.contains(piece.getId()),
                            reads));
        }
        return checks;
    }

    @Transactional(readOnly = true)
    public FeedbackCheck evaluateComposition(
            ReviewSnapshot review, long developerId, List<Result> support, FeedbackDestination destination) {
        var candidate = Feedback.builder()
                .id(review.id())
                .workspaceId(review.workspaceId())
                .reviewId(review.id())
                .developerId(developerId)
                .destination(destination)
                .itemId(
                        destination == FeedbackDestination.PRACTICE_PAGE
                                ? null
                                : review.item().itemId())
                .build();
        var ids = support.stream().map(Result::getId).distinct().toList();
        return evaluate(
                candidate,
                FeedbackCheckStage.COMPOSITION,
                false,
                support,
                ids.size(),
                !ids.isEmpty() && basis.hasIncorrectResults(review.workspaceId(), ids),
                false,
                new GateReads(review.workspaceId()));
    }

    private FeedbackCheck evaluate(
            Feedback feedback,
            FeedbackCheckStage stage,
            boolean checkApproval,
            List<Result> support,
            long expectedResults,
            boolean incorrect,
            boolean hasWriteFence,
            GateReads reads) {
        long workspaceId = feedback.getWorkspaceId();
        UUID feedbackId = feedback.getId();
        var review = reads.review(feedback.getReviewId());
        var policy = reads.policy(feedback.getDeveloperId());
        boolean inScope = policy.isPresent()
                && policy.get().getLeftAt() == null
                && (policy.get().getPeopleScope() != ReviewScopeMode.SELECTED
                        || policy.get().getSelected());
        boolean authorized = !support.isEmpty()
                && support.stream()
                        .allMatch(result -> Objects.equals(result.getDeveloperId(), feedback.getDeveloperId()))
                && support.stream().map(Result::getId).distinct().count() == expectedResults
                && reads.deliverable(support);
        var itemSnapshot = reads.snapshot(feedback);
        boolean current = support.stream().allMatch(reads::current) && currentItem(feedback, support, itemSnapshot);
        boolean modelAllowed = policy.isPresent() && policy.get().getAiChoice() != AiChoice.NO_AI;
        boolean deliveryEnabled = policy.isPresent()
                && policy.get().getDeliveryEnabled()
                && !reads.accountSuppressed(feedback.getDeveloperId());
        boolean approvalCurrent = !checkApproval || !requiresApproval(feedback);
        if (checkApproval && !approvalCurrent) {
            var approval = decisions.findLatestApproval(workspaceId, feedbackId);
            approvalCurrent = approval.filter(row -> row.getAction() == FeedbackDecisionAction.APPROVE
                            && Objects.equals(row.getContentDigest(), digests.current(workspaceId, feedbackId)))
                    .isPresent();
        }
        long admitted = review.map(row -> row.getRolloutRevision()).orElse(0L);
        long evaluated = policy.map(FeedbackBasisRepository.PolicyRow::getRolloutRevision)
                .orElse(0L);
        var facts = new FeedbackCheckFacts(
                admitted,
                evaluated,
                inScope,
                authorized,
                modelAllowed,
                deliveryEnabled,
                incorrect,
                current,
                approvalCurrent,
                hasWriteFence);
        FeedbackStopReason refusal = null;
        if (review.isEmpty() || review.get().getStatus() == ReviewStatus.CANCELLED)
            refusal = FeedbackStopReason.REVIEW_CANCELLED;
        else if (review.get().getOrigin() == ReviewOrigin.BACKFILL) refusal = FeedbackStopReason.BACKFILL_QUIET;
        else if (policy.isEmpty()
                || !policy.get().getEnabled()
                || policy.get().getWorkspaceStatus() != WorkspaceStatus.ACTIVE)
            refusal = FeedbackStopReason.WORKSPACE_DISABLED;
        else if (admitted != evaluated) refusal = FeedbackStopReason.STALE_ROLLOUT_REVISION;
        else if (!inScope) refusal = FeedbackStopReason.OUTSIDE_CURRENT_SCOPE;
        else if (reads.accountSuppressed(feedback.getDeveloperId())) refusal = FeedbackStopReason.PERSON_SUPPRESSED;
        else if (!modelAllowed) refusal = FeedbackStopReason.NO_AI;
        else if (policy.get().getAccountStatus() != Account.Status.ACTIVE
                && policy.get().getAccountStatus() != Account.Status.UNCLAIMED)
            refusal = FeedbackStopReason.RECIPIENT_UNAVAILABLE;
        else if (!deliveryEnabled) refusal = FeedbackStopReason.DELIVERY_OFF;
        else if (reads.modes(support).contains(FeedbackMode.OFF)) refusal = FeedbackStopReason.FEEDBACK_OFF;
        else if (incorrect) refusal = FeedbackStopReason.RESULT_MARKED_INCORRECT;
        else if (!current) refusal = FeedbackStopReason.REVIEWED_VERSION_CHANGED;
        else if (!eligibleState(feedback, support, itemSnapshot, reads))
            refusal = FeedbackStopReason.ITEM_STATE_INELIGIBLE;
        else if (!authorized) refusal = FeedbackStopReason.EVIDENCE_NOT_PERMITTED;
        else if (!approvalCurrent) refusal = FeedbackStopReason.APPROVAL_STALE;
        else if (policy.get().getDeliveryStatus() == PracticeDeliveryStatus.PAUSED)
            refusal = FeedbackStopReason.WORKSPACE_DELIVERY_PAUSED;
        else if (feedback.getDestination() == FeedbackDestination.ON_WORK && silentMode.isSilentModeEngaged())
            refusal = FeedbackStopReason.INSTANCE_SILENCED;
        return new FeedbackCheck(stage, refusal == null, refusal, clock.instant(), facts);
    }

    public boolean requiresApproval(Feedback feedback) {
        return feedback.getDestination() == FeedbackDestination.ON_WORK
                && modes(feedback).contains(FeedbackMode.APPROVE_FIRST);
    }

    public boolean off(Feedback feedback) {
        return modes(feedback).contains(FeedbackMode.OFF);
    }

    private List<FeedbackMode> modes(Feedback feedback) {
        var support = results.findByWorkspaceIdAndIdIn(
                feedback.getWorkspaceId(),
                basis
                        .findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(
                                feedback.getWorkspaceId(), feedback.getId())
                        .stream()
                        .map(FeedbackBasis::getResultId)
                        .distinct()
                        .toList());
        return modes(support, feedback.getWorkspaceId());
    }

    public FeedbackMode mode(Result result) {
        return modes(List.of(result), result.getWorkspaceId()).getFirst();
    }

    private List<FeedbackMode> modes(List<Result> support, long workspaceId) {
        var modes = feedbackModes.effectiveModes(
                workspaceId, support.stream().map(Result::getPracticeId).toList());
        return support.stream()
                .map(result -> modes.getOrDefault(result.getPracticeId(), FeedbackMode.OFF))
                .toList();
    }

    private Optional<ItemSnapshot> currentSnapshot(long workspaceId, long itemId) {
        var rows = basis.findItems(workspaceId, List.of(itemId));
        if (rows.size() != 1 || rows.getFirst().getRemovedAt() != null) return Optional.empty();
        var row = rows.getFirst();
        var ref = new ItemRef(
                workspaceId,
                row.getId(),
                new ItemKind(row.getKind()),
                row.getSourceId(),
                row.getParentId(),
                row.getPath());
        var matching = sources.stream().filter(source -> source.supports(ref)).toList();
        return matching.size() == 1 ? matching.getFirst().current(ref) : Optional.empty();
    }

    private static boolean currentItem(Feedback feedback, List<Result> support, Optional<ItemSnapshot> snapshot) {
        Long itemId = feedback.getItemId();
        if (itemId == null) return true;
        if (snapshot.isEmpty()) return false;
        return support.stream()
                .filter(result -> result.getItemId() == itemId)
                .allMatch(result -> snapshot.get().version().value().equals(result.getItemVersion()));
    }

    private static boolean eligibleState(
            Feedback feedback, List<Result> support, Optional<ItemSnapshot> snapshot, GateReads reads) {
        if (feedback.getDestination() != FeedbackDestination.ON_WORK || feedback.getItemId() == null) return true;
        if (snapshot.isEmpty()) return false;
        for (var result : support) {
            if (result.getPracticeVersionId() == null) return false;
            var version = reads.version(result.getPracticeVersionId());
            if (version.isEmpty()) return false;
            var required = version.get().getDefinition().path("reviewOn").path("state");
            if (!required.isObject()) return false;
            for (String key : required.propertyNames()) {
                var allowed = required.path(key);
                String value = snapshot.get().state().get(key);
                if (value == null || !allowed.isArray()) return false;
                boolean matches = false;
                for (var candidate : allowed)
                    if (candidate.isString() && value.equals(candidate.asString())) matches = true;
                if (!matches) return false;
            }
        }
        return true;
    }

    /**
     * The lookups of one gate read. Pieces of one recipient ask the same questions: the same policy, the same
     * practices and versions, the same work. Each answer is read once per read and dropped with it, so a later read
     * never sees a stale one.
     */
    private final class GateReads {
        private final long workspaceId;
        private final Map<UUID, Optional<Review>> reviewsById = new HashMap<>();
        private final Map<Long, Optional<FeedbackBasisRepository.PolicyRow>> policies = new HashMap<>();
        private final Map<Long, Boolean> suppressed = new HashMap<>();
        private final Map<Long, Optional<PracticeVersion>> versionsById = new HashMap<>();
        private final Map<Long, Optional<Practice>> practicesById = new HashMap<>();
        private final Map<Long, Optional<ItemSnapshot>> snapshots = new HashMap<>();
        private final Map<Long, FeedbackMode> modesByPractice = new HashMap<>();
        // Filled by prefetch for a batch; a single evaluation asks the evidence check for its own support.
        private @Nullable Set<UUID> deliverable;

        GateReads(long workspaceId) {
            this.workspaceId = workspaceId;
        }

        void prefetch(List<Feedback> pieces, Collection<Result> support) {
            reviews
                    .findAllById(pieces.stream()
                            .map(Feedback::getReviewId)
                            .distinct()
                            .toList())
                    .stream()
                    .filter(review -> review.getWorkspaceId() == workspaceId)
                    .forEach(review -> reviewsById.put(review.getId(), Optional.of(review)));
            pieces.forEach(piece -> reviewsById.putIfAbsent(piece.getReviewId(), Optional.empty()));
            deliverable = support.isEmpty()
                    ? Set.of()
                    : evidence.permitsForNewDelivery(workspaceId, support, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
            modes(List.copyOf(support));
        }

        Optional<Review> review(UUID id) {
            return reviewsById.computeIfAbsent(id, key -> reviews.findByIdAndWorkspaceId(key, workspaceId));
        }

        Optional<FeedbackBasisRepository.PolicyRow> policy(long developerId) {
            return policies.computeIfAbsent(developerId, key -> basis.policy(workspaceId, key));
        }

        boolean accountSuppressed(long developerId) {
            return suppressed.computeIfAbsent(developerId, suppression::isAccountSuppressed);
        }

        boolean deliverable(List<Result> support) {
            var known = deliverable;
            if (known == null)
                return evidence.permitsForNewDelivery(workspaceId, support, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)
                                .size()
                        == support.size();
            return support.stream().allMatch(result -> known.contains(result.getId()));
        }

        Optional<PracticeVersion> version(long id) {
            return versionsById.computeIfAbsent(id, key -> versions.findByIdAndWorkspaceId(key, workspaceId));
        }

        Optional<ItemSnapshot> snapshot(Feedback feedback) {
            Long itemId = feedback.getItemId();
            if (itemId == null) return Optional.empty();
            return snapshots.computeIfAbsent(itemId, key -> currentSnapshot(workspaceId, key));
        }

        boolean current(Result result) {
            Long versionId = result.getPracticeVersionId();
            if (result.getEndedAt() != null || versionId == null || result.isMigratedHistory()) return false;
            var version = version(versionId);
            var practice = practicesById.computeIfAbsent(
                    result.getPracticeId(), key -> practices.findByIdAndWorkspaceId(key, workspaceId));
            return version.isPresent()
                    && practice.isPresent()
                    && version.get()
                            .getReviewDigest()
                            .equals(practice.get().getCurrentVersion().getReviewDigest());
        }

        List<FeedbackMode> modes(List<Result> support) {
            var missing = support.stream()
                    .map(Result::getPracticeId)
                    .filter(practiceId -> !modesByPractice.containsKey(practiceId))
                    .distinct()
                    .toList();
            if (!missing.isEmpty()) {
                var effective = feedbackModes.effectiveModes(workspaceId, missing);
                missing.forEach(practiceId ->
                        modesByPractice.put(practiceId, effective.getOrDefault(practiceId, FeedbackMode.OFF)));
            }
            return support.stream()
                    .map(result -> Objects.requireNonNull(modesByPractice.get(result.getPracticeId())))
                    .toList();
        }
    }
}
