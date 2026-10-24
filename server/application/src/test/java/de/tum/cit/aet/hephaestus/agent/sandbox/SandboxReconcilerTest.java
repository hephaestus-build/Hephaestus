package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** The removal rules of {@link SandboxReconciler}, whatever the runtime. */
class SandboxReconcilerTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(1));

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private SandboxBackend backend;

    private SimpleMeterRegistry meterRegistry;
    private SandboxReconciler reconciler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new SandboxReconciler(jobRepository, backend, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void activeJobs(UUID... ids) {
        when(jobRepository.findByStatusIn(List.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING)))
                .thenReturn(Arrays.stream(ids)
                        .map(id -> {
                            var job = new ReviewJob();
                            job.setId(id);
                            return job;
                        })
                        .toList());
    }

    private static SandboxBackend.Listed review(String name, UUID job, @Nullable Instant created) {
        return new SandboxBackend.Listed(
                new SandboxBackend.Handle(name), SandboxBackend.Kind.REVIEW, job, 1, created, false);
    }

    private static SandboxBackend.Listed host(String name, boolean creatorGone) {
        return new SandboxBackend.Listed(
                new SandboxBackend.Handle(name),
                SandboxBackend.Kind.HOST,
                UUID.randomUUID(),
                null,
                LONG_AGO,
                creatorGone);
    }

    private double sweeps(String outcome) {
        return meterRegistry
                .counter(AgentMetrics.SANDBOX_RECONCILER_SWEEPS, "outcome", outcome)
                .count();
    }

    @Test
    void shouldRemoveTheReviewsOfJobsThatEndedOnceTheyAreOlderThanTheGrace() {
        UUID queued = UUID.randomUUID();
        activeJobs(queued);
        when(backend.list())
                .thenReturn(List.of(
                        review("active", queued, LONG_AGO),
                        review("ended", UUID.randomUUID(), LONG_AGO),
                        review("young", UUID.randomUUID(), NOW.minusSeconds(30)),
                        review("ageless", UUID.randomUUID(), null)));

        reconciler.periodicReconciliation();

        verify(backend).remove(new SandboxBackend.Handle("ended"));
        verify(backend, never()).remove(new SandboxBackend.Handle("active"));
        verify(backend, never()).remove(new SandboxBackend.Handle("young"));
        verify(backend, never()).remove(new SandboxBackend.Handle("ageless"));
        assertThat(meterRegistry
                        .counter(AgentMetrics.SANDBOX_RECONCILER_ORPHANED, "resource", "sandbox")
                        .count())
                .isEqualTo(1.0);
        assertThat(sweeps("completed")).isEqualTo(1.0);
    }

    @Test
    void shouldRemoveAHostOnlyOnceItsWorkerIsGone() {
        activeJobs();
        when(backend.list()).thenReturn(List.of(host("gone", true), host("alive", false)));

        reconciler.onStartup();

        verify(backend).remove(new SandboxBackend.Handle("gone"));
        verify(backend, never()).remove(new SandboxBackend.Handle("alive"));
    }

    @Test
    void shouldRemoveNothingWhenTheJobsCannotBeRead() {
        when(jobRepository.findByStatusIn(any())).thenThrow(new IllegalStateException("database down"));

        reconciler.periodicReconciliation();

        verify(backend, never()).list();
        assertThat(sweeps("skipped")).isEqualTo(1.0);
    }

    @Test
    void shouldRemoveNothingWhenTheSandboxesCannotBeListed() {
        activeJobs();
        when(backend.list()).thenThrow(new SandboxException("daemon gone"));

        reconciler.periodicReconciliation();

        verify(backend, never()).remove(any());
        assertThat(sweeps("skipped")).isEqualTo(1.0);
        assertThat(sweeps("completed")).isZero();
    }

    @Test
    void shouldGoOnAfterARemovalThatFailed() {
        activeJobs();
        when(backend.list())
                .thenReturn(List.of(
                        review("stuck", UUID.randomUUID(), LONG_AGO), review("next", UUID.randomUUID(), LONG_AGO)));
        doThrow(new SandboxException("stuck")).when(backend).remove(new SandboxBackend.Handle("stuck"));

        reconciler.periodicReconciliation();

        verify(backend).remove(new SandboxBackend.Handle("next"));
        assertThat(sweeps("completed")).isEqualTo(1.0);
    }

    @Test
    void shouldTimeThePeriodicSweep() {
        activeJobs();
        when(backend.list()).thenReturn(List.of());

        reconciler.periodicReconciliation();

        assertThat(meterRegistry.timer(AgentMetrics.SANDBOX_RECONCILER_DURATION).count())
                .isEqualTo(1);
    }
}
