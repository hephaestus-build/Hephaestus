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
import java.util.ArrayList;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The repeats that the readback of a piece on the work flags: each of its notes, found and not resolved on the
 * provider, that cites a problem with the same practice and identity as a note of another review on the same work.
 * That note was delivered, its copy is posted, its piece is not withdrawn, and Hephaestus did not close it as fixed.
 * The check before publication saw only the history it read, so this catches a note posted on the work after it. Pairs
 * inside one composition are left out: the check already listed them, and the composer chose.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackReadbackRepeats {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
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
        var pieces = feedback.findDeliveredOnWorkOfOtherReviews(
                workspaceId, row.getDeveloperId(), itemId, row.getReviewId());
        var withdrawn = visibility
                .withdrawnAt(
                        workspaceId,
                        pieces.stream().map(FeedbackRepository.OnWorkPiece::id).toList())
                .keySet();
        var reviewOf = new HashMap<UUID, UUID>();
        pieces.stream()
                .filter(piece -> !withdrawn.contains(piece.id()))
                .forEach(piece -> reviewOf.put(piece.id(), piece.reviewId()));
        var others = posted(workspaceId, reviewOf.keySet());
        if (others.isEmpty()) return List.of();
        reviewOf.put(row.getId(), row.getReviewId());
        var traces = traces(workspaceId, reviewOf);
        var notes = new ArrayList<FeedbackCopy>(own);
        notes.addAll(others);
        var support = new HashMap<UUID, List<FeedbackCompositionTrace.Support>>();
        for (var note : notes) {
            var trace = traces.get(note.getFeedbackId());
            support.put(note.getId(), trace == null ? List.of() : NoteCitations.support(trace, note));
        }
        var cited = support.values().stream().flatMap(List::stream).toList();
        var authorized = publicHistory.authorizeAll(
                workspaceId,
                results.findByWorkspaceIdAndIdIn(
                        workspaceId,
                        cited.stream()
                                .map(FeedbackCompositionTrace.Support::resultId)
                                .distinct()
                                .toList()),
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
                            copyable(support.getOrDefault(other.getId(), List.of()), authorized)
                                    ? other.getBody()
                                    : null))));
        }
        return repeats;
    }

    /**
     * The posted notes of these pieces that Hephaestus did not close as fixed, one for each provider comment: an edit
     * keeps the comment, and its newer revision, which comes later, speaks for it.
     */
    private List<FeedbackCopy> posted(long workspaceId, Set<UUID> pieces) {
        if (pieces.isEmpty()) return List.of();
        var notes = copies.findByWorkspaceIdAndFeedbackIdInOrderByPositionAsc(workspaceId, pieces).stream()
                .filter(copy -> NoteCitations.note(copy)
                        && copy.getState() == FeedbackCopyState.POSTED
                        && copy.getExternalRef() != null)
                .toList();
        if (notes.isEmpty()) return List.of();
        var closed = new HashSet<>(copies.findClosedAsFixed(
                workspaceId, notes.stream().map(FeedbackCopy::getId).toList()));
        var byComment = new LinkedHashMap<String, FeedbackCopy>();
        notes.stream()
                .filter(copy -> !closed.contains(copy.getId()))
                .forEach(copy -> byComment.put(Objects.requireNonNull(copy.getExternalRef()), copy));
        return List.copyOf(byComment.values());
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

    /** The other note's words may be copied only when the read may use every result it rests on, as the history. */
    private static boolean copyable(
            List<FeedbackCompositionTrace.Support> support, Map<UUID, FeedbackPublicHistory.AuthorizedSupport> read) {
        return !support.isEmpty()
                && support.stream().allMatch(basis -> {
                    var entry = read.get(basis.resultId());
                    return entry != null && entry.custody().complete();
                });
    }
}
