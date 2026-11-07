package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ReviewJobRecoveryTest {

    @ParameterizedTest(name = "staged={0}")
    @ValueSource(booleans = {true, false})
    void shouldGiveOnlyARetainedPracticeOneMoreAttemptToSealAtTheRetryLimit(boolean staged) {
        var jobs = mock(ReviewJobRepository.class);
        var properties = mock(AgentProperties.class);
        when(properties.maxRetries()).thenReturn(5);
        var continuation = mock(ReviewCompositionContinuation.class);
        var job = RuntimeReviewTestData.job();
        UUID lease = UUID.randomUUID();
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(lease);
        job.setAttempt(5);
        when(jobs.lockById(job.getId())).thenReturn(Optional.of(job));
        when(continuation.hasStaged(job)).thenReturn(staged);
        // As the query does: it requeues only while the spent attempts stay below the allowance.
        when(jobs.requeueOrphan(eq(job.getId()), eq(lease), any(Integer.class), any(Instant.class)))
                .thenAnswer(call -> job.spentAttempts() < call.getArgument(2, Integer.class) ? 1 : 0);
        var recovery = new ReviewJobRecovery(
                jobs,
                properties,
                new JsonMapper(),
                mock(ReviewAttemptUsageService.class),
                Optional.of(continuation),
                new SimpleMeterRegistry());

        assertThat(recovery.retryUnadmitted(job, lease, false)).isEqualTo(staged);

        // With practices retained, the next claim only seals them, as it does after a lost worker.
        verify(jobs).requeueOrphan(eq(job.getId()), eq(lease), eq(staged ? 6 : 5), any(Instant.class));
    }
}
