package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.StatusBucketCount;
import de.tum.cit.aet.hephaestus.core.time.TimeBuckets;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.BucketFeedbackCounts;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.PracticeFeedbackCounts;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.PracticeObservationCounts;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewPracticeGroupDTO;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How practice reviews went in a time range: reviews by {@code created_at}, observations by {@code observed_at} and
 * feedback by {@code created_at}, the columns the admin lists filter by. Every total therefore matches the list
 * filtered to the same range and value, and each practice's observation and feedback counts match the list
 * filtered to the same range, practice and value.
 */
@Service
@RequiredArgsConstructor
class PracticeReviewOverviewService {

    private final AgentJobRepository agentJobRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public PracticeReviewOverviewDTO overview(long workspaceId, TimeBuckets buckets) {
        TimeRange range = buckets.range();
        int size = buckets.starts().size();
        long[] reviewsByBucket = new long[size];
        long[] observationsByBucket = new long[size];
        long[] feedbackByBucket = new long[size];
        Map<AgentJobStatus, Long> reviews = new EnumMap<>(AgentJobStatus.class);
        ReviewFeedbackCountsDTO feedback = ReviewFeedbackCountsDTO.empty();
        if (size > 0) {
            Long[] starts = buckets.epochSeconds();
            for (StatusBucketCount row : agentJobRepository.countByBucketAndStatus(
                    workspaceId, AgentPurpose.PRACTICE_REVIEW, range.from(), range.to(), starts)) {
                reviewsByBucket[row.getBucket() - 1] += row.getCount();
                reviews.merge(row.getStatus(), row.getCount(), Long::sum);
            }
            for (var row :
                    observationRepository.countObservationsByBucket(workspaceId, range.from(), range.to(), starts)) {
                observationsByBucket[row.getBucket() - 1] += row.getCount();
            }
            for (BucketFeedbackCounts row :
                    feedbackRepository.summarizeFeedbackByBucket(workspaceId, range.from(), range.to(), starts)) {
                feedbackByBucket[row.getBucket() - 1] += row.getTotal();
                feedback = feedback.plus(ReviewFeedbackCountsDTO.from(row));
            }
        }

        Map<Long, PracticeFeedbackCounts> practiceFeedback =
                feedbackRepository.summarizeFeedbackByPractice(workspaceId, range.from(), range.to()).stream()
                        .collect(Collectors.toMap(PracticeFeedbackCounts::getPracticeId, Function.identity()));
        List<PracticeObservationCounts> practiceObservations = observationRepository.summarizeObservationsByPractice(
                workspaceId, range.from(), range.to(), practiceFeedback.keySet().toArray(Long[]::new));
        List<PracticeReviewCountsDTO> practices = practiceObservations.stream()
                .map(row -> new PracticeReviewCountsDTO(
                        row.getPracticeSlug(),
                        row.getPracticeName(),
                        ReviewPracticeGroupDTO.from(
                                row.getGroupSlug(), row.getGroupName(), row.getGroupIcon(), row.getGroupColor()),
                        ReviewObservationCountsDTO.from(row),
                        row.getInvalidated(),
                        ReviewFeedbackCountsDTO.from(practiceFeedback.get(row.getPracticeId()))))
                .toList();

        return new PracticeReviewOverviewDTO(
                range.from(),
                range.to(),
                buckets.size(),
                ReviewRunCountsDTO.from(reviews),
                practices.stream()
                        .map(PracticeReviewCountsDTO::observations)
                        .reduce(ReviewObservationCountsDTO.empty(), ReviewObservationCountsDTO::plus),
                practices.stream()
                        .mapToLong(PracticeReviewCountsDTO::observationsInvalidated)
                        .sum(),
                feedback,
                IntStream.range(0, size)
                        .mapToObj(i -> new PracticeReviewBucketDTO(
                                buckets.starts().get(i),
                                reviewsByBucket[i],
                                observationsByBucket[i],
                                feedbackByBucket[i]))
                        .toList(),
                practices);
    }
}
