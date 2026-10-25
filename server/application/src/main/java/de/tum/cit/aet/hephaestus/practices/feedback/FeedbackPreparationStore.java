package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackPreparationService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedReviewOnWork;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedReviewQuery;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackPreparationStore implements PreparedReviewQuery {
    private static final String PREPARATION_RECEIPTS = "feedbackPreparationReceipts";
    private static final int MAX_REVIEW_CHECK_BYTES = 65_536;
    private final FeedbackRepository feedback;
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ReviewRepository reviews;
    private final Clock clock;
    private final PersonDataWriteFence privacy;
    private final FeedbackPreparationService preparation;
    private final FeedbackLedgerRecorder ledger;
    private final FeedbackContentDigest digests;
    private final FeedbackDeliveryPolicy policy;

    public PreparedPackage prepareAll(long workspaceId, UUID reviewId, List<FeedbackDraft> drafts) {
        if (workspaceId <= 0
                || drafts.size() > 30
                || drafts.stream()
                        .anyMatch(draft -> draft.workspaceId() != workspaceId
                                || !draft.reviewId().equals(reviewId))
                || drafts.stream().map(FeedbackDraft::id).distinct().count() != drafts.size())
            throw new IllegalArgumentException(
                    "A preparation batch needs one workspace/review and distinct feedback ids.");
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Review", reviewId.toString()));
        if (review.getKind() != ReviewKind.ITEM) throw conflict("A scan cannot prepare developer feedback.");
        if (review.getStatus() != ReviewStatus.RUNNING
                && drafts.stream()
                        .anyMatch(draft -> feedback.findByIdAndWorkspaceId(draft.id(), workspaceId)
                                .isEmpty())) throw conflict("This review is no longer running.");
        Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var pieces = drafts.stream()
                .map(draft -> preparePiece(review, draft, acceptedAt))
                .toList();
        String packageDigest = digests.orderedPackageDigest(
                pieces.stream().map(PreparedFeedback::packageDigest).toList());
        Instant preparedAt = preparationReceipt(review, packageDigest, acceptedAt);
        return new PreparedPackage(
                packageDigest,
                preparedAt,
                pieces.stream()
                        .map(piece -> new PreparedFeedback(piece.feedbackId(), piece.packageDigest(), preparedAt))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreparedReviewOnWork> preparedReviewOnWork(long workspaceId, UUID reviewId) {
        var review = reviews.findByIdAndWorkspaceId(reviewId, workspaceId).orElse(null);
        if (review == null) return Optional.empty();
        JsonNode compositions = review.getChecks().path("feedbackComposition");
        return feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(workspaceId, reviewId).stream()
                .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK
                        && compositions.path(row.getId().toString()).isObject())
                .findFirst()
                .map(row -> new PreparedReviewOnWork(
                        row.getBody(),
                        copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, row.getId()).stream()
                                .filter(copy -> copy.getKind() == FeedbackCopyKind.LINE_NOTE)
                                .flatMap(copy -> note(copy).stream())
                                .toList(),
                        new JsonMapper()
                                .treeToValue(
                                        compositions.path(row.getId().toString()), FeedbackCompositionTrace.class)));
    }

    private static Optional<PreparedReviewOnWork.Note> note(FeedbackCopy copy) {
        String body = copy.getBody();
        String path = copy.getPath();
        if (body == null || path == null) return Optional.empty();
        return Optional.of(new PreparedReviewOnWork.Note(
                copy.getPosition(),
                body,
                new FeedbackAnchor(path, copy.getStartLine(), copy.getEndLine(), copy.getSide())));
    }

    /** What this review's prepared private pieces decide on the admitted results, and the room they were composed to use. */
    @Transactional(readOnly = true)
    public PrivateFeedbackProgress privateProgress(
            long workspaceId, UUID reviewId, long developerId, Collection<UUID> admittedResultIds) {
        if (admittedResultIds.isEmpty()) return PrivateFeedbackProgress.NONE;
        var decided = new HashSet<UUID>();
        var byDestination = new EnumMap<FeedbackDestination, Set<UUID>>(FeedbackDestination.class);
        var delivering = new HashMap<UUID, FeedbackDestination>();
        for (var row : basis.findPrivateDecisions(workspaceId, reviewId, developerId, admittedResultIds)) {
            decided.add(row.getResultId());
            byDestination
                    .computeIfAbsent(row.getDestination(), ignored -> new HashSet<>())
                    .add(row.getResultId());
            if (row.getDelivering()) delivering.put(row.getFeedbackId(), row.getDestination());
        }
        return new PrivateFeedbackProgress(
                decided,
                Collections.frequency(delivering.values(), FeedbackDestination.PRACTICE_PAGE),
                Collections.frequency(delivering.values(), FeedbackDestination.CONVERSATION),
                byDestination);
    }

    private Instant preparationReceipt(Review review, String packageDigest, Instant acceptedAt) {
        var checks = (ObjectNode) review.getChecks().deepCopy();
        var recorded = checks.path(PREPARATION_RECEIPTS);
        if (!recorded.isMissingNode() && !recorded.isObject())
            throw conflict("Preparation receipt history is not an object.");
        var receipts =
                recorded.isMissingNode() ? JsonNodeFactory.instance.objectNode() : (ObjectNode) recorded.deepCopy();
        var existing = receipts.path(packageDigest);
        if (!existing.isMissingNode()) {
            if (!existing.isString()) throw conflict("The preparation receipt has no recorded timestamp.");
            return Instant.parse(existing.asString());
        }
        if (review.getStatus() != ReviewStatus.RUNNING)
            throw conflict("This preparation was not accepted before closure.");
        receipts.put(packageDigest, acceptedAt.toString());
        checks.set(PREPARATION_RECEIPTS, receipts);
        if (checks.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REVIEW_CHECK_BYTES)
            throw conflict("Preparation receipt history exceeds the review checks limit.");
        review.setChecks(checks);
        reviews.saveAndFlush(review);
        return acceptedAt;
    }

    private PreparedFeedback preparePiece(Review review, FeedbackDraft proposal, Instant preparedAt) {
        var existing = feedback.findByIdAndWorkspaceId(proposal.id(), proposal.workspaceId());
        if (proposal.composition() != null) {
            var checks = (ObjectNode) review.getChecks().deepCopy();
            var compositions = checks.withObject("feedbackComposition");
            var incoming = new JsonMapper().valueToTree(proposal.composition());
            var retained = compositions.path(proposal.id().toString());
            if (!retained.isMissingNode() && !retained.equals(incoming))
                throw conflict("The prepared composition support and quiet decisions are immutable.");
            compositions.set(proposal.id().toString(), incoming);
            if (checks.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REVIEW_CHECK_BYTES)
                throw conflict("Composition history exceeds the review checks limit.");
            review.setChecks(checks);
            reviews.saveAndFlush(review);
        }
        if (proposal.developerId() <= 0 || proposal.workspaceId() <= 0)
            throw new IllegalArgumentException("Feedback needs positive workspace and developer ids.");
        if (proposal.destination() == FeedbackDestination.ON_WORK
                && !Objects.equals(proposal.itemId(), review.getItemId()))
            throw conflict("The feedback must address this review's item.");
        if (proposal.state() != FeedbackState.READY
                && proposal.state() != FeedbackState.AWAITING_APPROVAL
                && proposal.state() != FeedbackState.WITHHELD)
            throw new IllegalArgumentException("Preparation cannot claim a provider write.");
        if (proposal.state() == FeedbackState.WITHHELD && proposal.stopReason() == null)
            throw new IllegalArgumentException("Withheld feedback needs a reason.");
        if (proposal.basis().isEmpty()) throw new IllegalArgumentException("Feedback needs an admitted result basis.");
        var prepared = preparation.prepare(proposal, existing);
        var draft = prepared.draft();
        if (draft.state() != FeedbackState.WITHHELD
                && (draft.body() == null && draft.copies().isEmpty()))
            throw new IllegalArgumentException("Prepared feedback needs its body and result basis.");
        var support = results.findByWorkspaceIdAndIdIn(
                draft.workspaceId(),
                draft.basis().stream()
                        .map(FeedbackBasisInput::resultId)
                        .distinct()
                        .toList());
        var byId = support.stream().collect(Collectors.toMap(Result::getId, r -> r));
        var edges = draft.basis().stream()
                .map(input -> {
                    var result = byId.get(input.resultId());
                    if (result == null || !Objects.equals(result.getDeveloperId(), draft.developerId()))
                        throw conflict("Feedback basis belongs to another developer.");
                    UUID problemId = input.problemId();
                    if (problemId != null
                            && problems.findByIdAndWorkspaceId(problemId, draft.workspaceId())
                                    .filter(problem -> problem.getResultId().equals(result.getId()))
                                    .isEmpty()) throw conflict("A cited problem does not belong to the result.");
                    if (input.ordinal() < 0) throw new IllegalArgumentException("Basis order must be nonnegative.");
                    return FeedbackBasis.builder()
                            .id(FeedbackIds.basis(draft.id(), input.resultId(), problemId))
                            .workspaceId(draft.workspaceId())
                            .feedbackId(draft.id())
                            .resultId(input.resultId())
                            .problemId(problemId)
                            .developerId(draft.developerId())
                            .ordinal(input.ordinal())
                            .build();
                })
                .toList();
        var planned = draft.copies().stream()
                .map(input -> planned(draft, input, prepared.withheldCopies().get(input.id())))
                .toList();
        if (planned.stream().map(FeedbackCopy::getPosition).distinct().count() != planned.size()
                || edges.stream().map(FeedbackBasis::getId).distinct().count() != edges.size())
            throw new IllegalArgumentException("Feedback references must be distinct.");
        if (draft.destination() == FeedbackDestination.PRACTICE_PAGE && !planned.isEmpty())
            throw new IllegalArgumentException("Practice-page feedback has no physical copy.");
        if (draft.destination() == FeedbackDestination.CONVERSATION
                && (planned.size() != 1 || planned.getFirst().getKind() != FeedbackCopyKind.CHAT_TURN))
            throw new IllegalArgumentException("Conversation feedback needs one chat-turn copy.");
        if (draft.destination() == FeedbackDestination.ON_WORK) {
            long summaries = planned.stream()
                    .filter(c -> c.getKind() == FeedbackCopyKind.SUMMARY)
                    .count();
            if (planned.stream().anyMatch(c -> c.getKind() == FeedbackCopyKind.CHAT_TURN)
                    || summaries > 1
                    || (draft.state() != FeedbackState.WITHHELD && (draft.body() != null) != (summaries == 1)))
                throw new IllegalArgumentException("On-work copies must match their summary and anchored text.");
        }
        var row = Feedback.builder()
                .id(draft.id())
                .workspaceId(draft.workspaceId())
                .reviewId(draft.reviewId())
                .itemId(draft.itemId())
                .developerId(draft.developerId())
                .destination(draft.destination())
                .state(draft.state())
                .stopReason(draft.stopReason())
                .body(draft.body())
                .checks(Map.copyOf(draft.checks()))
                .createdAt(preparedAt)
                .build();
        if (!privacy.holdForAccountWrite(draft.developerId())) throw conflict("Recipient processing is suppressed.");
        if (existing.isPresent()) {
            if (!sameContent(
                    existing.get(),
                    row,
                    edges,
                    planned,
                    basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(draft.workspaceId(), draft.id()),
                    copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(draft.workspaceId(), draft.id())))
                throw conflict("This feedback id already has different content.");
            return preparedReceipt(existing.get(), preparedAt);
        }
        ledger.record(row, edges, planned);
        policy.prepared(row);
        preparation.recorded(
                prepared,
                feedback.findByIdAndWorkspaceId(draft.id(), draft.workspaceId()).orElseThrow());
        return preparedReceipt(row, preparedAt);
    }

    private PreparedFeedback preparedReceipt(Feedback row, Instant preparedAt) {
        return new PreparedFeedback(row.getId(), digests.current(row.getWorkspaceId(), row.getId()), preparedAt);
    }

    private FeedbackCopy planned(
            FeedbackDraft draft, FeedbackCopyInput input, @Nullable FeedbackStopReason copyReason) {
        var reason = draft.state() == FeedbackState.WITHHELD ? draft.stopReason() : copyReason;
        if (!input.id().equals(FeedbackIds.copy(draft.id(), input.kind(), input.position()))
                || !input.writeKey().equals(input.id().toString()))
            throw new IllegalArgumentException("Copy identity must match its prepared position.");
        if (input.writeMarker() != null && !input.writeMarker().equals(FeedbackIds.writeMarker(input.id())))
            throw new IllegalArgumentException("Copy marker must match its identity.");
        if (input.kind() == FeedbackCopyKind.CHAT_TURN && input.body() != null)
            throw new IllegalArgumentException("Chat copy words are recorded from the completed assistant message.");
        if (input.kind() == FeedbackCopyKind.SUMMARY && input.body() != null)
            throw new IllegalArgumentException("The summary body belongs to the feedback.");
        boolean anchored = input.kind() == FeedbackCopyKind.LINE_NOTE || input.kind() == FeedbackCopyKind.FILE_NOTE;
        if (input.position() < 0
                || (anchored
                        && (input.body() == null
                                || input.body().isBlank()
                                || input.path() == null
                                || input.path().isBlank())))
            throw new IllegalArgumentException("An anchored copy needs its original words and path.");
        if (anchored != (input.path() != null)
                || (!anchored && (input.startLine() != null || input.endLine() != null || input.side() != null)))
            throw new IllegalArgumentException("Copy coordinates must match its form.");
        if ((input.startLine() != null
                        && (input.startLine() <= 0 || input.endLine() == null || input.endLine() < input.startLine()))
                || (input.startLine() == null && input.endLine() != null)
                || (input.kind() == FeedbackCopyKind.LINE_NOTE && input.startLine() == null))
            throw new IllegalArgumentException("Copy line range is invalid.");
        return FeedbackCopy.builder()
                .id(input.id())
                .workspaceId(draft.workspaceId())
                .feedbackId(draft.id())
                .kind(input.kind())
                .position(input.position())
                .body(input.body())
                .path(input.path())
                .startLine(input.startLine())
                .endLine(input.endLine())
                .side(input.side())
                .state(reason == null ? FeedbackCopyState.PLANNED : FeedbackCopyState.WITHHELD)
                .stopReason(reason)
                .writeKey(input.writeKey())
                .writeMarker(input.writeMarker())
                .createdAt(clock.instant())
                .build();
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }

    private record BasisContent(UUID resultId, @Nullable UUID problemId, int ordinal) {}

    private record CopyContent(
            UUID id,
            int position,
            @Nullable String body,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable CitationSide side,
            @Nullable String writeKey,
            @Nullable String writeMarker) {}

    private static List<BasisContent> basisContent(List<FeedbackBasis> edges) {
        return edges.stream()
                .sorted(Comparator.comparingInt(FeedbackBasis::getOrdinal)
                        .thenComparing(edge -> edge.getId().toString()))
                .map(edge -> new BasisContent(edge.getResultId(), edge.getProblemId(), edge.getOrdinal()))
                .toList();
    }

    private static List<CopyContent> copyContent(List<FeedbackCopy> copies) {
        return copies.stream()
                .sorted(Comparator.comparingInt(FeedbackCopy::getPosition))
                .map(copy -> new CopyContent(
                        copy.getId(),
                        copy.getPosition(),
                        copy.getKind() == FeedbackCopyKind.CHAT_TURN ? null : copy.getBody(),
                        copy.getPath(),
                        copy.getStartLine(),
                        copy.getEndLine(),
                        copy.getSide(),
                        copy.getWriteKey(),
                        copy.getWriteMarker()))
                .toList();
    }

    private static boolean sameContent(
            Feedback stored,
            Feedback incoming,
            List<FeedbackBasis> incomingBasis,
            List<FeedbackCopy> incomingCopies,
            List<FeedbackBasis> storedBasis,
            List<FeedbackCopy> storedCopies) {
        return stored.getWorkspaceId() == incoming.getWorkspaceId()
                && stored.getReviewId().equals(incoming.getReviewId())
                && Objects.equals(stored.getItemId(), incoming.getItemId())
                && stored.getDeveloperId() == incoming.getDeveloperId()
                && stored.getDestination() == incoming.getDestination()
                && Objects.equals(stored.getBody(), incoming.getBody())
                && basisContent(incomingBasis).equals(basisContent(storedBasis))
                && copyContent(incomingCopies).equals(copyContent(storedCopies));
    }
}
