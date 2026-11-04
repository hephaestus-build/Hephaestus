package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackSupersession {
    private final FeedbackRepository feedback;
    private final FeedbackOutcomes outcomes;
    private final FeedbackCopyRepository copies;
    private final FeedbackVisibilityService visibility;
    private final FeedbackBasisRepository basis;
    private final ResultRepository results;
    private final ReviewRepository reviews;

    public boolean canReplaceConversation(long workspaceId, UUID previousId, long developerId, long practiceId) {
        var previous = feedback.lockByIdAndWorkspaceId(previousId, workspaceId).orElse(null);
        Long practice = previous == null ? null : replaceablePractice(previous, developerId);
        return practice != null && practice == practiceId;
    }

    /**
     * The practice queued conversation feedback may still be replaced for: its recorded primary result's practice, or
     * else the one practice all its support shares. Null when the piece cannot be replaced.
     */
    @Nullable
    Long replaceablePractice(Feedback previous, long developerId) {
        long workspaceId = previous.getWorkspaceId();
        UUID previousId = previous.getId();
        if (previous.getDeveloperId() != developerId
                || previous.getDestination() != FeedbackDestination.CONVERSATION
                || previous.getState() != FeedbackState.READY
                || previous.getDeliveredAt() != null
                || visibility.withdrawn(workspaceId, previousId)
                || copies.hasWriteFence(workspaceId, previousId)) return null;
        var ids = basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspaceId, previousId).stream()
                .map(FeedbackBasis::getResultId)
                .distinct()
                .toList();
        var support = results.findByWorkspaceIdAndIdIn(workspaceId, ids);
        var primary = reviews.findByIdAndWorkspaceId(previous.getReviewId(), workspaceId)
                .orElseThrow()
                .getChecks()
                .path("feedbackComposition")
                .path(previousId.toString())
                .path("primaryResultId");
        if (primary.isString())
            return support.stream()
                    .filter(result -> result.getId().toString().equals(primary.asString()))
                    .map(Result::getPracticeId)
                    .findFirst()
                    .orElse(null);
        var practices = support.stream().map(Result::getPracticeId).distinct().toList();
        return practices.size() == 1 ? practices.getFirst() : null;
    }

    @Transactional
    public boolean replaceOpen(long workspaceId, UUID previousId, UUID nextId) {
        if (previousId.equals(nextId)) return false;
        var first = feedback.lockByIdAndWorkspaceId(
                        previousId.toString().compareTo(nextId.toString()) < 0 ? previousId : nextId, workspaceId)
                .orElseThrow();
        var second = feedback.lockByIdAndWorkspaceId(
                        previousId.toString().compareTo(nextId.toString()) < 0 ? nextId : previousId, workspaceId)
                .orElseThrow();
        var prior = first.getId().equals(previousId) ? first : second;
        var next = first.getId().equals(nextId) ? first : second;
        if (prior.getDeveloperId() != next.getDeveloperId()
                || prior.getDestination() != next.getDestination()
                || prior.getDestination() == FeedbackDestination.ON_WORK)
            throw new IllegalArgumentException("Replacement must address the same private destination and developer.");
        if (next.getState() != FeedbackState.READY && next.getState() != FeedbackState.DELIVERED) return false;
        if ((prior.getState() != FeedbackState.READY && prior.getState() != FeedbackState.DELIVERED)
                || visibility.withdrawn(workspaceId, previousId)
                || (prior.getDestination() == FeedbackDestination.CONVERSATION
                        && copies.hasWriteFence(workspaceId, previousId))) return false;
        feedback.recordReplacement(workspaceId, nextId, previousId);
        outcomes.update(prior, FeedbackState.REPLACED, null, prior.getDeliveredAt());
        return true;
    }
}
