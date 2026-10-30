package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.time.TimeBuckets;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.dto.GroupRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewBucketDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPracticeCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewResultCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewStatusCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.TimeBucketSize;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewOverviewService {
    private final ReviewOverviewRepository counts;
    private final PracticeRepository practices;
    private final Clock clock;

    public TimeRange allTime(long ws, @Nullable Instant to) {
        var now = clock.instant();
        var end = to == null || to.isAfter(now) ? now : to;
        return new TimeRange(counts.first(ws).filter(t -> t.isBefore(end)).orElse(end), end);
    }

    public ReviewOverviewDTO overview(long ws, TimeBuckets buckets) {
        var range = buckets.range();
        var outcomes = counts.outcomes(ws, range.from(), range.to());
        var feedback = counts.feedback(ws, range.from(), range.to());
        var incorrect = counts.incorrect(ws, range.from(), range.to());
        var status = counts.statuses(ws, range.from(), range.to()).stream()
                .collect(Collectors.toMap(
                        ReviewOverviewRepository.StatusCount::getStatus,
                        ReviewOverviewRepository.StatusCount::getCount));
        var groupedBuckets = counts.buckets(ws, range.from(), range.to(), buckets.epochSeconds()).stream()
                .collect(Collectors.toMap(ReviewOverviewRepository.BucketCount::getBucket, b -> b));
        var timeline = IntStream.range(0, buckets.starts().size())
                .mapToObj(i -> {
                    var b = groupedBuckets.get(i + 1);
                    return new ReviewBucketDTO(
                            buckets.starts().get(i),
                            b == null ? 0 : b.getReviews(),
                            b == null ? 0 : b.getResults(),
                            b == null ? 0 : b.getFeedback());
                })
                .toList();
        var activePractices = Stream.concat(
                        outcomes.stream().map(ReviewOverviewRepository.OutcomeCount::getPracticeId),
                        feedback.stream().map(ReviewOverviewRepository.FeedbackCount::getPracticeId))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var byPractice = practices.findByWorkspaceId(ws).stream()
                .filter(p -> activePractices.contains(p.getId()))
                .map(p -> {
                    var g = p.getGroup();
                    return new ReviewPracticeCountsDTO(
                            p.getSlug(),
                            Objects.requireNonNull(p.getCurrentVersion().getName()),
                            g == null ? null : new GroupRefDTO(g.getSlug(), g.getName(), g.getIcon(), g.getColor()),
                            outcomes(outcomes, p.getId()),
                            marked(incorrect, p.getId()),
                            feedback(feedback, p.getId()));
                })
                .toList();
        return new ReviewOverviewDTO(
                range.from(),
                range.to(),
                TimeBucketSize.valueOf(buckets.size().name()),
                new ReviewStatusCountsDTO(
                        status.getOrDefault(ReviewStatus.QUEUED, 0),
                        status.getOrDefault(ReviewStatus.RUNNING, 0),
                        status.getOrDefault(ReviewStatus.COMPLETE, 0),
                        status.getOrDefault(ReviewStatus.PARTIAL, 0),
                        status.getOrDefault(ReviewStatus.FAILED, 0),
                        status.getOrDefault(ReviewStatus.CANCELLED, 0)),
                outcomes(outcomes, null),
                marked(incorrect, null),
                feedback(feedback, null),
                timeline,
                byPractice);
    }

    private static ReviewResultCountsDTO outcomes(
            List<ReviewOverviewRepository.OutcomeCount> rows, @Nullable Long practice) {
        var c = rows.stream()
                .filter(r -> Objects.equals(r.getPracticeId(), practice))
                .collect(Collectors.toMap(
                        ReviewOverviewRepository.OutcomeCount::getOutcome,
                        ReviewOverviewRepository.OutcomeCount::getCount));
        return new ReviewResultCountsDTO(
                c.getOrDefault(ResultOutcome.MET, 0),
                c.getOrDefault(ResultOutcome.NOT_MET, 0),
                c.getOrDefault(ResultOutcome.DOES_NOT_APPLY, 0),
                c.getOrDefault(ResultOutcome.COULD_NOT_DETERMINE, 0));
    }

    private static ReviewFeedbackCountsDTO feedback(
            List<ReviewOverviewRepository.FeedbackCount> rows, @Nullable Long practice) {
        var c = rows.stream()
                .filter(r -> Objects.equals(r.getPracticeId(), practice))
                .collect(Collectors.toMap(
                        ReviewOverviewRepository.FeedbackCount::getState,
                        ReviewOverviewRepository.FeedbackCount::getCount));
        return new ReviewFeedbackCountsDTO(
                c.getOrDefault(FeedbackState.AWAITING_APPROVAL, 0),
                c.getOrDefault(FeedbackState.READY, 0),
                c.getOrDefault(FeedbackState.DELIVERED, 0),
                c.getOrDefault(FeedbackState.UNCONFIRMED, 0),
                c.getOrDefault(FeedbackState.WITHHELD, 0),
                c.getOrDefault(FeedbackState.REJECTED, 0),
                c.getOrDefault(FeedbackState.REPLACED, 0),
                c.getOrDefault(FeedbackState.FAILED, 0));
    }

    private static int marked(List<ReviewOverviewRepository.MarkedCount> rows, @Nullable Long practice) {
        return rows.stream()
                .filter(r -> Objects.equals(r.getPracticeId(), practice))
                .mapToInt(ReviewOverviewRepository.MarkedCount::getCount)
                .sum();
    }
}
