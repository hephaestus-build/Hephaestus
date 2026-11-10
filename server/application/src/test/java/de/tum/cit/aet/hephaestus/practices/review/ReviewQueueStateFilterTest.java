package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.repository.Query;

/** The review list's queue state filter must split the jobs as the queue's own counts do. */
class ReviewQueueStateFilterTest extends BaseUnitTest {

    @ParameterizedTest
    @EnumSource(ReviewQueueState.class)
    @DisplayName("each queue state filters on the condition the queue counts it by")
    void shouldMatchTheQueueCounts(ReviewQueueState state) throws NoSuchMethodException {
        String counts = ReviewRuntimeRepository.class
                .getMethod("queueCountsAt", long.class, Instant.class, Instant.class, Instant.class)
                .getAnnotation(Query.class)
                .value();
        // The list reads the hold from the job's run (r); the counts read it through the job's formula.
        String condition = ReviewQueriesImpl.queueStatePredicate(state).replace("r.hold_reason", "j.holdReason");

        // How queueCountsAt adds up the jobs of one figure.
        assertThat(counts).contains("SUM(CASE WHEN " + condition + " THEN 1L ELSE 0L END)");
    }
}
