package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunSummaryRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.ReviewFeedbackCounts;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ReviewObservationCounts;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewObservationCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ReviewRunSummaryQueryService {

    private final AgentJobRepository agentJobRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final ReviewRunTargets reviewRunTargets;

    @Transactional(readOnly = true)
    public Page<ReviewRunSummaryDTO> list(Long workspaceId, ReviewRunFilterParams filter, Pageable pageable) {
        Page<ReviewRunSummaryRow> reviews = agentJobRepository.findReviewRunSummaries(
                workspaceId, AgentPurpose.PRACTICE_REVIEW, filter, filter.from(), filter.to(), pageable);
        if (reviews.isEmpty()) {
            return new PageImpl<>(List.of(), reviews.getPageable(), reviews.getTotalElements());
        }
        var jobIds = reviews.stream().map(ReviewRunSummaryRow::getId).toList();
        Map<UUID, Target> targets = reviewRunTargets.of(workspaceId, reviews.getContent());
        Map<UUID, ReviewObservationCounts> observationCounts =
                observationRepository.summarizeReviewObservations(workspaceId, jobIds).stream()
                        .collect(Collectors.toMap(ReviewObservationCounts::getJobId, Function.identity()));
        Map<UUID, ReviewFeedbackCounts> feedbackCounts =
                feedbackRepository.summarizeReviewFeedback(workspaceId, jobIds).stream()
                        .collect(Collectors.toMap(ReviewFeedbackCounts::getJobId, Function.identity()));
        return reviews.map(review -> ReviewRunSummaryDTO.from(
                review,
                Objects.requireNonNull(targets.get(review.getId())),
                ReviewObservationCountsDTO.from(observationCounts.get(review.getId())),
                ReviewFeedbackCountsDTO.from(feedbackCounts.get(review.getId()))));
    }
}
