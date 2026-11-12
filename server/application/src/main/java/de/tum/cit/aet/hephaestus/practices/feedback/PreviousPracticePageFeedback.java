package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.FeedbackClosure;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PreviousPracticePageFeedback {

    private final FeedbackRepository feedbackRepository;
    private final PracticePageFeedbackBasis basis;
    private final FeedbackResponseRepository responses;
    private final FeedbackVisibilityService visibility;

    public record Previous(
            UUID id, Instant preparedAt, @Nullable Instant closedAt, boolean withdrawn) {
        public boolean isOpen() {
            return closedAt == null && !withdrawn;
        }

        public Instant nextEvidenceSince(Instant windowStart) {
            Instant leftOff = closedAt == null ? preparedAt : closedAt;
            return leftOff.isAfter(windowStart) ? leftOff : windowStart;
        }
    }

    @Transactional(readOnly = true)
    public Optional<Previous> find(long workspaceId, long developerId, String practiceSlug, Instant now) {
        var previous = feedbackRepository.findLatestPracticePage(workspaceId, developerId, practiceSlug);
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        var feedback = previous.getFirst();
        var evidence = basis.visibleEvidence(workspaceId, List.of(feedback.getId()));
        if (!evidence.containsKey(feedback.getId())) {
            return Optional.empty();
        }
        var changedAt = basis.practiceChangedAt(evidence);
        var work = basis.workResolutions(workspaceId, List.of(feedback), evidence, changedAt, now)
                .getOrDefault(feedback.getId(), WorkResolution.NONE);
        var answer = responses
                .findCurrent(workspaceId, feedback.getId())
                .map(FeedbackResponseDTO::from)
                .orElse(null);
        var closure = FeedbackClosure.of(
                work.resolvedAt(),
                PracticePageFeedbackBasis.resolvedByDeveloperAt(answer),
                changedAt.get(feedback.getId()));
        return Optional.of(new Previous(
                feedback.getId(),
                feedback.getCreatedAt(),
                closure == null ? null : closure.at(),
                visibility.withdrawn(workspaceId, feedback.getId())));
    }
}
