package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.FeedbackClosure;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-app feedback a developer was last shown about one practice, as the lane that writes the next one
 * needs it: whether it is still open, so the next card replaces it rather than piling up beside it, and
 * where the next card's evidence starts, so that work the developer was already shown is never cited again.
 *
 * <p>Closed the way the card reports it ({@link FeedbackClosure}). A practice never written about has no
 * previous card; the caller's own window applies then.
 */
@Component
@RequiredArgsConstructor
public class PreviousInAppFeedback {

    private final FeedbackRepository feedbackRepository;
    private final InAppFeedbackEvidence feedbackEvidence;
    private final ReactionRepository reactionRepository;

    /**
     * The newest readable card about one practice.
     *
     * @param closedAt when it stopped being open, or {@code null} while it is
     */
    public record Previous(
            UUID id, Instant preparedAt, @Nullable Instant closedAt) {
        /** Still on the developer's page as something to work on — what a newer card about the habit replaces. */
        public boolean isOpen() {
            return closedAt == null;
        }

        /**
         * Where the next card's evidence starts: after this card closed when it has, and otherwise after it
         * was prepared, since the next card replaces it and the work it already cited is not news — but never
         * before {@code windowStart}, which a card older than the window cannot pull back.
         */
        public Instant nextEvidenceSince(Instant windowStart) {
            Instant leftOff = closedAt != null ? closedAt : preparedAt;
            return leftOff.isAfter(windowStart) ? leftOff : windowStart;
        }
    }

    /** The newest readable card about the practice, closed or open as of {@code now}. */
    @Transactional(readOnly = true)
    public Optional<Previous> find(Long workspaceId, Long recipientUserId, String practiceSlug, Instant now) {
        List<Feedback> previous = feedbackRepository.findReadableInAppForPractice(
                workspaceId, recipientUserId, practiceSlug, PageRequest.of(0, 1));
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        Feedback feedback = previous.getFirst();
        Map<UUID, List<Observation>> evidence =
                feedbackEvidence.visibleEvidence(workspaceId, List.of(feedback.getId()));
        Map<UUID, Instant> practiceChangedAt = feedbackEvidence.practiceChangedAt(evidence);
        Instant byWork = feedbackEvidence
                .workResolutions(workspaceId, recipientUserId, List.of(feedback), evidence, practiceChangedAt, now)
                .getOrDefault(feedback.getId(), WorkResolution.NONE)
                .resolvedAt();
        Instant byDeveloper = InAppFeedbackEvidence.resolvedByDeveloperAt(reactionRepository
                .findCurrentResponse(feedback.getId(), recipientUserId)
                .map(response -> FeedbackResponseDTO.from(feedback.getId(), response))
                .orElse(null));
        FeedbackClosure closure = FeedbackClosure.of(byWork, byDeveloper, practiceChangedAt.get(feedback.getId()));
        return Optional.of(
                new Previous(feedback.getId(), feedback.getCreatedAt(), closure == null ? null : closure.at()));
    }
}
