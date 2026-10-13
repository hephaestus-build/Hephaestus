package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerCapacityReporterTest extends BaseUnitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final WorkerControlClient client = mock(WorkerControlClient.class);

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
    }

    @Test
    void emitsCapacityReportSnapshot() {
        WorkerProperties props = WorkerPropertiesFixtures.minimal("3", "2");
        WorkerCapacityState state = new WorkerCapacityState(props);
        state.claimReview();
        WorkerCapacityReporter reporter = new WorkerCapacityReporter(state, client, props, scheduler, registry);

        reporter.tick();

        ArgumentCaptor<WorkerControlFrame> frame = ArgumentCaptor.forClass(WorkerControlFrame.class);
        verify(client).send(frame.capture());
        CapacityReport report = (CapacityReport) frame.getValue();
        assertThat(report.reviewMax()).isEqualTo(3);
        assertThat(report.inFlightReview()).isEqualTo(1);
        assertThat(report.spareReview()).isEqualTo(2);
        assertThat(registry.counter("worker.heartbeats.sent").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsFailedCounterOnPublisherException() {
        WorkerProperties props = WorkerPropertiesFixtures.minimal("1", "1");
        WorkerCapacityState state = new WorkerCapacityState(props);
        doThrow(new RuntimeException("boom")).when(client).send(any());
        WorkerCapacityReporter reporter = new WorkerCapacityReporter(state, client, props, scheduler, registry);

        reporter.tick();

        // A failed tick must NOT propagate — the scheduler thread would die.
        assertThat(registry.counter("worker.heartbeats.failed").count()).isEqualTo(1.0);
        assertThat(registry.counter("worker.heartbeats.sent").count()).isZero();
    }
}
