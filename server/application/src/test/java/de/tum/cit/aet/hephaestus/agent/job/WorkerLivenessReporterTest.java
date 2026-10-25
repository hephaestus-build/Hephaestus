package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkerLivenessReporterTest extends BaseUnitTest {

    private final WorkerRegistryRepository registry = mock(WorkerRegistryRepository.class);
    private final ReviewJobExecutor executor = mock(ReviewJobExecutor.class);

    @Test
    void shouldStopEveryAttemptTheLedgerNoLongerRunsHere() {
        var lost = new ReviewJobExecutor.LostAttempt(UUID.randomUUID(), 2);
        var other = new ReviewJobExecutor.LostAttempt(UUID.randomUUID(), 0);
        when(executor.lostAttemptsToStop()).thenReturn(List.of(lost, other));

        reporter(Runnable::run).beat();

        verify(registry).heartbeat("test-worker");
        verify(executor).stopLostAttempt(lost);
        verify(executor).stopLostAttempt(other);
    }

    @Test
    void shouldReadTheLedgerOffTheHeartbeatAndQueueOnlyOneCheck() {
        List<Runnable> queued = new ArrayList<>();
        var reporter = reporter(queued::add);

        reporter.beat();
        reporter.beat();

        verify(registry, times(2)).heartbeat("test-worker");
        assertThat(queued).as("one check waits; the heartbeat read nothing").hasSize(1);
        verify(executor, times(0)).lostAttemptsToStop();

        queued.removeFirst().run();
        reporter.beat();

        verify(executor).lostAttemptsToStop();
        assertThat(queued).as("the finished check lets the next one queue").hasSize(1);
    }

    @Test
    void shouldKeepTheHeartbeatWhenTheLedgerCannotBeRead() {
        when(executor.lostAttemptsToStop()).thenThrow(new QueryTimeoutException("ledger unavailable"));
        var reporter = reporter(Runnable::run);

        assertThatCode(reporter::beat).doesNotThrowAnyException();
        reporter.beat();

        verify(registry, times(2)).heartbeat("test-worker");
        verify(executor, times(2)).lostAttemptsToStop();
    }

    @Test
    void shouldStillCheckTheLedgerWhenTheHeartbeatFails() {
        doThrow(new QueryTimeoutException("registry unavailable"))
                .when(registry)
                .heartbeat("test-worker");

        assertThatCode(reporter(Runnable::run)::beat).doesNotThrowAnyException();

        verify(executor).lostAttemptsToStop();
    }

    private WorkerLivenessReporter reporter(Executor stops) {
        var properties = mock(AgentProperties.class);
        when(properties.heartbeatInterval()).thenReturn(Duration.ofSeconds(25));
        return new WorkerLivenessReporter(
                registry,
                executor,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                properties,
                WorkerPropertiesFixtures.minimal("1", "1"),
                new SimpleMeterRegistry(),
                stops);
    }
}
