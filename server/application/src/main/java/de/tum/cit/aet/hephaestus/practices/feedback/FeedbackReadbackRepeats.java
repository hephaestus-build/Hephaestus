package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The repeats that the readback of a piece on the work flags: each of its notes, found and not resolved on the
 * provider, that cites a problem with the same practice and identity as a note of another review on the same work.
 *
 * <p>The other note reached the work after this piece was prepared ({@code feedback.created_at}, the preparation
 * receipt's time). The check read the history before that, and preparation refuses a history that changed since, so an
 * older note was in the check's history and the composer chose. The other note is the newest revision of its provider
 * comment, posted, of a piece that is not withdrawn and not marked incorrect, does not rest on an incorrect result, and
 * Hephaestus did not close it as fixed. Its words are shown only as the public history shows them. Pairs inside one
 * composition are left out: the check listed them.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackReadbackRepeats {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackBasisRepository basis;
    private final FeedbackVisibilityService visibility;
    private final ReviewRepository reviews;
    private final ResultRepository results;
    private final FeedbackPublicHistory publicHistory;
    private final ObjectMapper mapper;

    List<FeedbackRepairStatus.Repeat> of(Feedback row, List<CopyReadback> readback) {
        long workspaceId = row.getWorkspaceId();
        Long itemId = row.getItemId();
        var standing = readback.stream()
                .filter(copy -> copy.status() == CopyReadback.Status.FOUND && !copy.resolved())
                .map(CopyReadback::copyId)
                .collect(Collectors.toSet());
        if (itemId == null || standing.isEmpty()) return List.of();
        var own = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, row.getId()).stream()
                .filter(copy -> NoteCitations.note(copy) && standing.contains(copy.getId()))
                .toList();
        if (own.isEmpty()) return List.of();
        var pieces = feedback.findDeliveredOnWorkOfOtherReviews(workspaceId, itemId, row.getReviewId());
        var others = standingNotes(workspaceId, pieces, row.getCreatedAt());
        if (others.isEmpty()) return List.of();
        var reviewOf = new HashMap<UUID, UUID>();
        pieces.forEach(piece -> reviewOf.put(piece.id(), piece.reviewId()));
        reviewOf.put(row.getId(), row.getReviewId());
        var traces = traces(workspaceId, reviewOf);
        var support = new HashMap<UUID, List<FeedbackCompositionTrace.Support>>();
        Stream.concat(own.stream(), others.stream()).forEach(note -> {
            var trace = traces.get(note.getFeedbackId());
            support.put(note.getId(), trace == null ? List.of() : NoteCitations.support(trace, note));
        });
        var basisOf = new HashMap<UUID, Set<UUID>>();
        basis.findReadRows(
                        workspaceId,
                        others.stream()
                                .map(FeedbackCopy::getFeedbackId)
                                .distinct()
                                .toList())
                .forEach(edge -> basisOf.computeIfAbsent(edge.feedbackId(), ignored -> new HashSet<>())
                        .add(edge.resultId()));
        var cited = support.values().stream().flatMap(List::stream).toList();
        var resultIds = new HashSet<UUID>();
        cited.forEach(entry -> resultIds.add(entry.resultId()));
        basisOf.values().forEach(resultIds::addAll);
        var authorized = publicHistory.authorizeAll(
                workspaceId,
                results.findByWorkspaceIdAndIdIn(workspaceId, List.copyOf(resultIds)),
                SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        var practices = new HashMap<UUID, String>();
        authorized.forEach((resultId, entry) -> practices.put(resultId, entry.practiceSlug()));
        var identities = publicHistory.identities(
                workspaceId,
                cited.stream()
                        .map(FeedbackCompositionTrace.Support::problemId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        var citing = new LinkedHashMap<NoteCitations.Cite, List<FeedbackCopy>>();
        for (var other : others) {
            var trace = traces.get(other.getFeedbackId());
            if (trace != null)
                NoteCitations.cites(trace, other, practices, identities)
                        .keySet()
                        .forEach(cite -> citing.computeIfAbsent(cite, ignored -> new ArrayList<>())
                                .add(other));
        }
        var repeats = new ArrayList<FeedbackRepairStatus.Repeat>();
        for (var note : own) {
            var trace = traces.get(note.getFeedbackId());
            if (trace == null) continue;
            var byOther = new LinkedHashMap<FeedbackCopy, Set<UUID>>();
            NoteCitations.cites(trace, note, practices, identities)
                    .forEach((cite, problems) -> citing.getOrDefault(cite, List.of())
                            .forEach(other -> byOther.computeIfAbsent(other, ignored -> new LinkedHashSet<>())
                                    .addAll(problems)));
            byOther.forEach((other, problems) -> repeats.add(new FeedbackRepairStatus.Repeat(
                    note.getId(),
                    List.copyOf(problems),
                    new FeedbackRepairStatus.OtherNote(
                            other.getPath(),
                            other.getStartLine(),
                            other.getEndLine(),
                            other.getExternalUrl(),
                            textAllowed(basisOf.getOrDefault(other.getFeedbackId(), Set.of()), authorized)
                                    ? other.getBody()
                                    : null))));
        }
        return repeats;
    }

    /**
     * The other notes that count: for each provider comment, the copy of its newest piece, posted after {@code since}.
     * An edit keeps the comment, so its newer revision speaks for it, also when that revision retracted it. Then the
     * copy must be posted, its piece not withdrawn or marked incorrect, and it may not rest on an incorrect result or be
     * closed as fixed.
     */
    private List<FeedbackCopy> standingNotes(
            long workspaceId, List<FeedbackRepository.OnWorkPiece> pieces, Instant since) {
        if (pieces.isEmpty()) return List.of();
        var ids = pieces.stream().map(FeedbackRepository.OnWorkPiece::id).toList();
        var newestFirst = new HashMap<UUID, Integer>();
        for (int index = 0; index < ids.size(); index++) newestFirst.put(ids.get(index), index);
        var all = copies.findByWorkspaceIdAndFeedbackIdInOrderByPositionAsc(workspaceId, ids).stream()
                .filter(copy -> copy.getExternalRef() != null)
                .sorted(Comparator.comparingInt(
                                (FeedbackCopy copy) -> newestFirst.getOrDefault(copy.getFeedbackId(), ids.size()))
                        .thenComparingInt(FeedbackCopy::getPosition))
                .toList();
        var newest = new LinkedHashMap<String, FeedbackCopy>();
        var firstPosted = new HashMap<String, Instant>();
        for (var copy : all) {
            String comment = Objects.requireNonNull(copy.getExternalRef());
            newest.putIfAbsent(comment, copy);
            var posted = copy.getPostedAt();
            if (posted != null)
                firstPosted.merge(comment, posted, (left, right) -> left.isBefore(right) ? left : right);
        }
        var excluded = new HashSet<>(visibility.withdrawnAt(workspaceId, ids).keySet());
        excluded.addAll(basis.incorrectFeedbackIds(workspaceId, ids));
        var incorrectCopies = copies.incorrectCopyIdsOf(workspaceId, ids);
        var candidates = newest.entrySet().stream()
                .filter(entry -> {
                    var copy = entry.getValue();
                    var posted = firstPosted.get(entry.getKey());
                    return NoteCitations.note(copy)
                            && copy.getState() == FeedbackCopyState.POSTED
                            && !excluded.contains(copy.getFeedbackId())
                            && !incorrectCopies.contains(copy.getId())
                            && posted != null
                            && posted.isAfter(since);
                })
                .map(Map.Entry::getValue)
                .toList();
        if (candidates.isEmpty()) return List.of();
        var closed = new HashSet<>(copies.findClosedAsFixed(
                workspaceId, candidates.stream().map(FeedbackCopy::getId).toList()));
        return candidates.stream()
                .filter(copy -> !closed.contains(copy.getId()))
                .toList();
    }

    private Map<UUID, FeedbackCompositionTrace> traces(long workspaceId, Map<UUID, UUID> reviewOf) {
        var byId = reviews.findByWorkspaceIdAndIdIn(workspaceId, Set.copyOf(reviewOf.values())).stream()
                .collect(Collectors.toMap(Review::getId, Function.identity()));
        var traces = new HashMap<UUID, FeedbackCompositionTrace>();
        reviewOf.forEach((feedbackId, reviewId) -> {
            var review = byId.get(reviewId);
            var trace = review == null ? null : NoteCitations.trace(mapper, review, feedbackId);
            if (trace != null) traces.put(feedbackId, trace);
        });
        return traces;
    }

    /**
     * The other note's words, as the public history allows them: the read may use every result of its piece's basis,
     * with complete custody. A withdrawn or incorrect piece never gets here.
     */
    private static boolean textAllowed(Set<UUID> pieceBasis, Map<UUID, FeedbackPublicHistory.AuthorizedSupport> read) {
        return !pieceBasis.isEmpty()
                && pieceBasis.stream().allMatch(resultId -> {
                    var entry = read.get(resultId);
                    return entry != null && entry.custody().complete();
                });
    }
}
