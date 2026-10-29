package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCancellationReason;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobExecutor;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

class WorkerDrainCoordinatorTest extends BaseUnitTest {

    @Test
    void shouldDrainBeforeWebServerGracefulShutdown() {
        // Spring stops higher phases first.
        assertThat(WorkerDrainCoordinator.PHASE).isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    void gracefulDrainAwaitsThenSucceeds() {
        WorkerProperties props = propsWithDrain(Duration.ofSeconds(5));
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        when(executor.awaitInFlight(any(Duration.class))).thenReturn(true);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        WorkerDrainCoordinator coordinator =
                new WorkerDrainCoordinator(props, Optional.of(executor), events, new SimpleMeterRegistry());
        coordinator.start();
        coordinator.stop();

        verify(executor).stopAcceptingNewJobs();
        verify(executor).awaitInFlight(Duration.ofSeconds(5));
        verify(executor, never()).cancelInFlight(any());
        assertThat(coordinator.isDraining()).isTrue();
        verifyReadinessStateRefusingTrafficWasPublished(events);
    }

    @Test
    void gracefulOverrunCancelsRemaining() {
        WorkerProperties props = propsWithDrain(Duration.ofSeconds(5));
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        when(executor.awaitInFlight(any(Duration.class))).thenReturn(false);

        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
                props, Optional.of(executor), mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        coordinator.start();
        coordinator.stop();

        verify(executor).cancelInFlight(ReviewJobCancellationReason.DRAIN_GRACEFUL);
    }

    @Test
    void shouldCompleteShutdownWithoutRepeatingCleanupWhenContainerStopsAreIncomplete() throws Exception {
        WorkerProperties props = propsWithDrain(Duration.ZERO);
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        when(executor.cancelInFlight(ReviewJobCancellationReason.DRAIN_IMMEDIATE))
                .thenReturn(false);
        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
                props, Optional.of(executor), mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        coordinator.start();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch bothCompleted = new CountDownLatch(2);
        Runnable callback = () -> {
            completed.incrementAndGet();
            bothCompleted.countDown();
        };

        coordinator.stop(callback);
        coordinator.stop(callback);

        assertThat(bothCompleted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isEqualTo(2);
        assertThat(coordinator.isRunning()).isFalse();
        assertThat(coordinator.isDraining()).isTrue();
        verify(executor, times(1)).cancelInFlight(ReviewJobCancellationReason.DRAIN_IMMEDIATE);
    }

    @Test
    void shouldRefuseNewJobsAndReturnBeforeTheReviewsInFlightEndWhenStoppedWithACallback() throws Exception {
        WorkerProperties props = propsWithDrain(Duration.ofSeconds(5));
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        CountDownLatch reviewsEnd = new CountDownLatch(1);
        when(executor.awaitInFlight(any(Duration.class)))
                .thenAnswer(invocation -> reviewsEnd.await(10, TimeUnit.SECONDS));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WorkerDrainCoordinator coordinator =
                new WorkerDrainCoordinator(props, Optional.of(executor), events, new SimpleMeterRegistry());
        coordinator.start();
        CountDownLatch stopped = new CountDownLatch(1);

        coordinator.stop(stopped::countDown);

        // Spring stops the other beans of the phase only once this returns: it must not wait for the reviews.
        assertThat(stopped.getCount()).as("the drain still waits").isEqualTo(1);
        verify(executor).stopAcceptingNewJobs();
        verifyReadinessStateRefusingTrafficWasPublished(events);
        reviewsEnd.countDown();
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        verify(executor).awaitInFlight(Duration.ofSeconds(5));
        verify(executor, never()).cancelInFlight(any());
    }

    @Test
    void immediateDrainSkipsAwait() {
        WorkerProperties props = propsWithDrain(Duration.ZERO);
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);

        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
                props, Optional.of(executor), mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        coordinator.start();
        coordinator.stop();

        verify(executor).stopAcceptingNewJobs();
        verify(executor, never()).awaitInFlight(any());
        verify(executor, times(1)).cancelInFlight(ReviewJobCancellationReason.DRAIN_IMMEDIATE);
    }

    @Test
    void drainIsIdempotent() {
        WorkerProperties props = propsWithDrain(Duration.ofSeconds(1));
        ReviewJobExecutor executor = mock(ReviewJobExecutor.class);
        when(executor.awaitInFlight(any(Duration.class))).thenReturn(true);

        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
                props, Optional.of(executor), mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        coordinator.start();
        coordinator.stop();
        coordinator.stop(); // re-entrancy

        verify(executor, times(1)).stopAcceptingNewJobs();
    }

    private static WorkerProperties propsWithDrain(Duration timeout) {
        return WorkerPropertiesFixtures.withDrain(timeout);
    }

    private static void verifyReadinessStateRefusingTrafficWasPublished(ApplicationEventPublisher events) {
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        boolean found = captor.getAllValues().stream()
                .anyMatch(e ->
                        e instanceof AvailabilityChangeEvent<?> a && a.getState() == ReadinessState.REFUSING_TRAFFIC);
        assertThat(found)
                .as("expected an AvailabilityChangeEvent(REFUSING_TRAFFIC) to be published")
                .isTrue();
    }
}
