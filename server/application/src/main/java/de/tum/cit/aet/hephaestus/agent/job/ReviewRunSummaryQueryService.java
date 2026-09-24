package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunSummaryRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.ReviewFeedbackCounts;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ReviewObservationCounts;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ReviewRunSummaryQueryService {

    private final AgentJobRepository agentJobRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public Page<ReviewRunSummaryDTO> list(Long workspaceId, ReviewRunFilterParams filter, Pageable pageable) {
        Page<ReviewRunSummaryRow> reviews = agentJobRepository.findReviewRunSummaries(
                workspaceId, AgentPurpose.PRACTICE_REVIEW, filter.status(), filter.from(), filter.to(), pageable);
        if (reviews.isEmpty()) {
            return reviews.map(this::withoutCounts);
        }
        var jobIds = reviews.stream().map(ReviewRunSummaryRow::getId).toList();
        Map<UUID, ReviewObservationCounts> observationCounts =
                observationRepository.summarizeReviewObservations(workspaceId, jobIds).stream()
                        .collect(Collectors.toMap(ReviewObservationCounts::getJobId, Function.identity()));
        Map<UUID, ReviewFeedbackCounts> feedbackCounts =
                feedbackRepository.summarizeReviewFeedback(workspaceId, jobIds).stream()
                        .collect(Collectors.toMap(ReviewFeedbackCounts::getJobId, Function.identity()));
        return reviews.map(
                review -> from(review, observationCounts.get(review.getId()), feedbackCounts.get(review.getId())));
    }

    private ReviewRunSummaryDTO from(
            ReviewRunSummaryRow review,
            @Nullable ReviewObservationCounts observationCounts,
            @Nullable ReviewFeedbackCounts feedbackCounts) {
        return ReviewRunSummaryDTO.from(
                review,
                observationCounts == null
                        ? ReviewObservationCountsDTO.empty()
                        : new ReviewObservationCountsDTO(
                                observationCounts.getStrengths(),
                                observationCounts.getProblems(),
                                observationCounts.getNotApplicable(),
                                observationCounts.getUndetermined()),
                feedbackCounts == null
                        ? ReviewFeedbackCountsDTO.empty()
                        : new ReviewFeedbackCountsDTO(
                                feedbackCounts.getPrepared(),
                                feedbackCounts.getDelivered(),
                                feedbackCounts.getSuperseded(),
                                feedbackCounts.getSuppressed(),
                                feedbackCounts.getFailed()));
    }

    private ReviewRunSummaryDTO withoutCounts(ReviewRunSummaryRow review) {
        return ReviewRunSummaryDTO.from(review, ReviewObservationCountsDTO.empty(), ReviewFeedbackCountsDTO.empty());
    }
}
