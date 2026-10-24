package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The first heartbeat runs in start(); an hour's interval keeps the scheduled ones out of these tests. */
class WorkerLivenessReporterTest extends BaseUnitTest {

    private final WorkerRegistryRepository registry = mock(WorkerRegistryRepository.class);
    private final ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
    private final WorkerLivenessReporter reporter = reporter();

    @AfterEach
    void stop() {
        reporter.stop();
    }

    @Test
    void shouldHandEveryAttemptTheLedgerNoLongerRunsHereToAStop() {
        var lost = new ReviewJobExecutor.LostAttempt(UUID.randomUUID(), 2);
        var other = new ReviewJobExecutor.LostAttempt(UUID.randomUUID(), 0);
        when(executor.lostAttemptsToStop()).thenReturn(List.of(lost, other));

        reporter.start();

        verify(registry).heartbeat("test-worker");
        // The stops run on their own thread, so the heartbeat never waits for a container.
        verify(executor, timeout(5_000)).stopLostAttempt(lost);
        verify(executor, timeout(5_000)).stopLostAttempt(other);
    }

    @Test
    void shouldKeepTheHeartbeatWhenTheLedgerCannotBeRead() {
        when(executor.lostAttemptsToStop()).thenThrow(new QueryTimeoutException("ledger unavailable"));

        assertThatCode(reporter::start).doesNotThrowAnyException();

        verify(registry).heartbeat("test-worker");
    }

    @Test
    void shouldStillCheckTheLedgerWhenTheHeartbeatFails() {
        doThrow(new QueryTimeoutException("registry unavailable"))
                .when(registry)
                .heartbeat("test-worker");

        assertThatCode(reporter::start).doesNotThrowAnyException();

        verify(executor).lostAttemptsToStop();
    }

    private WorkerLivenessReporter reporter() {
        var properties = mock(AgentProperties.class);
        when(properties.heartbeatInterval()).thenReturn(Duration.ofHours(1));
        return new WorkerLivenessReporter(
                registry,
                executor,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                properties,
                WorkerPropertiesFixtures.minimal("1", "1"),
                new SimpleMeterRegistry());
    }
}
