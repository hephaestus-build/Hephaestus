package de.tum.cit.aet.hephaestus.integration.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SyncJobServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;

    @Mock
    private SyncJobRepository syncJobRepository;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private Workspace workspace;
    private Connection connection;
    private SyncJobService service;
    private final Map<Long, SyncJob> store = new HashMap<>();
    private final AtomicLong idSequence = new AtomicLong(100L);
    private final MutableClock clock = MutableClock.atFixedInstant();

    @BeforeEach
    void setUp() {
        // Run the TransactionTemplate callback inline (execute + executeWithoutResult).
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient()
                .doAnswer(inv -> {
                    Consumer<TransactionStatus> action = inv.getArgument(0);
                    action.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        lenient().when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        connection = mock(Connection.class);
        lenient().when(connection.getId()).thenReturn(CONNECTION_ID);
        lenient().when(connection.getWorkspace()).thenReturn(workspace);
        lenient().when(connection.getKind()).thenReturn(IntegrationKind.GITHUB);
        lenient().when(connection.getState()).thenReturn(IntegrationState.ACTIVE);
        lenient()
                .when(connectionRepository.findByIdAndWorkspaceId(CONNECTION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(connection));

        lenient()
                .when(syncJobRepository.findAbandonedForConnection(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(syncJobRepository.findAbandoned(anyLong())).thenReturn(List.of());
        lenient()
                .when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        // In-memory "table" backing findById, so markRunning/completeJob/persistProgress (which all
        // re-fetch by id before mutating) operate on the SAME instance the test holds via Started.job().
        lenient().when(syncJobRepository.saveAndFlush(any())).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            job.setId(idSequence.incrementAndGet());
            store.put(job.getId(), job);
            return job;
        });
        lenient().when(syncJobRepository.save(any())).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            store.put(job.getId(), job);
            return job;
        });
        lenient()
                .when(syncJobRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
        lenient().when(syncJobRepository.markRunning(anyLong())).thenAnswer(inv -> {
            SyncJob job = store.get((Long) inv.getArgument(0));
            if (job == null || job.getStatus() != SyncJobStatus.PENDING) {
                return 0;
            }
            job.setStatus(SyncJobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            job.setHeartbeatAt(Instant.now());
            return 1;
        });
        lenient().when(syncJobRepository.findCancelFlags(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream()
                    .map(store::get)
                    .filter(Objects::nonNull)
                    .map(job -> new SyncJobRepository.CancelFlagProjection() {
                        @Override
                        public Long getId() {
                            return job.getId();
                        }

                        @Override
                        public boolean isCancelRequested() {
                            return job.isCancelRequested();
                        }
                    })
                    .toList();
        });
        // In-memory stand-in for the terminal write. Its compare-and-set semantics (a late writer cannot
        // overwrite an already-terminal row) are proven against real Postgres in
        // SyncJobActiveIndexIntegrationTest, not here — this fake only needs to update the shared store so
        // the service-orchestration assertions in this file can read the resulting status back.
        lenient()
                .when(syncJobRepository.completeActiveJob(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    SyncJob job = store.get((Long) inv.getArgument(0));
                    if (job == null || !SyncJobStatus.ACTIVE.contains(job.getStatus())) {
                        return 0;
                    }
                    job.setStatus(inv.getArgument(1));
                    job.setFinishedAt(Instant.now());
                    job.setErrorSummary(inv.getArgument(2));
                    job.setItemsProcessed(inv.getArgument(3));
                    job.setItemsTotal(inv.getArgument(4));
                    Map<String, Object> progress = inv.getArgument(5);
                    if (!progress.isEmpty()) {
                        job.setProgress(progress);
                    }
                    return 1;
                });

        service = new SyncJobService(
                syncJobRepository,
                connectionRepository,
                workspaceRepository,
                eventPublisher,
                transactionTemplate,
                clock);
    }

    private SyncJob newJob(long id, SyncJobStatus status) {
        SyncJob job = new SyncJob(
                workspace, connection, IntegrationKind.GITHUB, SyncJobType.INITIAL, SyncJobTrigger.MANUAL, null);
        job.setId(id);
        job.setStatus(status);
        return job;
    }

    @Test
    void beginJob_connectionAlreadyHasActiveJob_throwsConflictCarryingThatJob() {
        SyncJob active = newJob(5L, SyncJobStatus.RUNNING);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.beginJob(defaultRequest()))
                .isInstanceOf(SyncJobConflictException.class)
                .satisfies(e ->
                        assertThat(((SyncJobConflictException) e).activeJob()).isSameAs(active));

        verify(syncJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void beginJob_connectionNotActive_rejectsBeforeCreatingJob() {
        when(connection.getState()).thenReturn(IntegrationState.UNINSTALLED);

        assertThatThrownBy(() -> service.beginJob(defaultRequest()))
                .isInstanceOf(SyncStateConflictException.class)
                .hasMessageContaining("UNINSTALLED");

        verify(syncJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void beginJob_workspaceNotActive_rejectsBeforeLockingConnection() {
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);

        assertThatThrownBy(() -> service.beginJob(defaultRequest()))
                .isInstanceOf(SyncStateConflictException.class)
                .hasMessageContaining("PURGED");

        verify(connectionRepository, never()).acquireLifecycleLock(anyLong(), anyLong());
        verify(syncJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void beginJob_partialIndexRace_translatesDataIntegrityViolationToConflict() {
        SyncJob raced = newJob(6L, SyncJobStatus.PENDING);
        // First read (before insert attempt): no active job visible yet.
        // Second read (after the constraint violation): the concurrent winner is now visible.
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        // doThrow(...).when(mock) — NOT when(mock.method()).thenThrow(...) — because the latter's argument
        // expression (`saveAndFlush(any())`) is a real invocation that would run the setUp() answer stub
        // (with a null dummy argument) before this line even finishes evaluating, NPE-ing on job.getId().
        doThrow(new DataIntegrityViolationException("ux_sync_job_active"))
                .when(syncJobRepository)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.beginJob(defaultRequest()))
                .isInstanceOf(SyncJobConflictException.class)
                .satisfies(e ->
                        assertThat(((SyncJobConflictException) e).activeJob()).isSameAs(raced));
    }

    @Test
    void beginJob_inlineReapClearsAbandonedJobBeforeGuardCheck_thenSucceeds() {
        SyncJob abandoned = newJob(7L, SyncJobStatus.RUNNING);
        when(syncJobRepository.findAbandonedForConnection(CONNECTION_ID, 900)).thenReturn(List.of(abandoned));
        when(syncJobRepository.markAbandoned(7L, "Abandoned: no heartbeat (likely pod restart)", 900))
                .thenReturn(1);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.empty());

        SyncJobService.Started started = service.beginJob(defaultRequest());

        verify(syncJobRepository).markAbandoned(7L, "Abandoned: no heartbeat (likely pod restart)", 900);
        assertThat(started.job()).isNotNull();
        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.PENDING);
    }

    @Test
    void beginJob_partialIndexRace_reQueriesInFreshTxAndRethrowsWhenNoWinnerVisible() {
        // A DataIntegrityViolation whose fresh-tx re-read still shows NO active row is a genuine
        // integrity error, not a lost race — it must propagate rather than be masked as a 409-absorb.
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("ux_sync_job_active"))
                .when(syncJobRepository)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.beginJob(defaultRequest()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void executeBody_normalReturn_marksSucceeded() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {});

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(started.job().getFinishedAt()).isNotNull();
        verify(syncJobRepository).pruneOldJobs(CONNECTION_ID, 50);
        verify(eventPublisher)
                .publishEvent(new SyncStateChangedEvent(
                        WORKSPACE_ID, CONNECTION_ID, IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.RESOURCES));
    }

    @Test
    void executeBody_duplicateDispatch_invokesBodyOnlyOnce() {
        SyncJobService.Started started = beginTestJob();
        AtomicInteger invocations = new AtomicInteger();

        service.executeBody(started, handle -> invocations.incrementAndGet());
        service.executeBody(started, handle -> invocations.incrementAndGet());

        assertThat(invocations).hasValue(1);
    }

    @Test
    void executeBody_concurrentDuplicate_doesNotUnregisterTheRunningHandle() throws Exception {
        SyncJobService.Started started = beginTestJob();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> service.executeBody(started, handle -> {
                bodyEntered.countDown();
                try {
                    releaseBody.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(bodyEntered.await(2, TimeUnit.SECONDS)).isTrue();

            service.executeBody(started, handle -> {});
            service.refreshLeases();

            verify(syncJobRepository).touchHeartbeat(List.of(started.job().getId()));
            releaseBody.countDown();
            running.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void stop_requestsCooperativeCancellationWithoutInterruptingTheRunner() throws Exception {
        // Scheduled syncs run on VIRTUAL threads, where an interrupt closes the JDBC socket out from
        // under pgjdbc — so the runner's own terminal write would die and the row would stick RUNNING
        // through every deploy. stop() must therefore only raise the flag: cooperative, never forced.
        SyncJobService.Started started = beginTestJob();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch stopRequested = new CountDownLatch(1);
        AtomicBoolean interruptFlagSeen = new AtomicBoolean();
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        service.start();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> service.executeBody(started, handle -> {
                bodyEntered.countDown();
                try {
                    assertThat(stopRequested.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("runner was interrupted", e);
                }
                cancellationObserved.set(handle.isCancellationRequested());
                interruptFlagSeen.set(Thread.currentThread().isInterrupted());
                handle.reportCancelled();
            }));
            assertThat(bodyEntered.await(2, TimeUnit.SECONDS)).isTrue();

            service.stop();
            stopRequested.countDown();

            running.get(2, TimeUnit.SECONDS);
        }

        assertThat(cancellationObserved).isTrue();
        assertThat(interruptFlagSeen)
                .as("stop() must not interrupt the runner thread")
                .isFalse();
        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(started.job().getFinishedAt()).isNotNull();
        verify(syncJobRepository).markCancelRequested(started.job().getId(), SyncJobStatus.ACTIVE);
    }

    @Test
    void stop_racingABodyThatAlreadyFinishedItsWork_marksSucceededNotCancelled() throws Exception {
        // The deploy-time interleaving: the body returns normally, and stop() fires while the job is
        // still registered (deregistration happens in executeBody's finally). The canceller must not be
        // able to stamp CANCELLED over a sync that did all of its work and advanced its watermarks —
        // that would also hide it from lastSuccessfulJob.
        SyncJobService.Started started = beginTestJob();
        CountDownLatch bodyReturning = new CountDownLatch(1);
        CountDownLatch stopFired = new CountDownLatch(1);
        service.start();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> service.executeBody(started, handle -> {
                // Body completed its work; hand control to stop() before executeBody maps the outcome.
                bodyReturning.countDown();
                try {
                    assertThat(stopFired.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(bodyReturning.await(2, TimeUnit.SECONDS)).isTrue();

            service.stop();
            stopFired.countDown();

            running.get(2, TimeUnit.SECONDS);
        }

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(started.handle().cancelledReported())
                .as("only the runner may report that it aborted")
                .isFalse();
    }

    @Test
    void executeBody_bodyThrowsWhileShuttingDown_keepsTheRealFailureInsteadOfMaskingItAsCancelled() {
        // A vendor 500 that happens to land during a deploy is a vendor 500. Reporting CANCELLED with a
        // null summary here would discard the only evidence of what actually broke — and assert a cause
        // ("stopped for shutdown") that isn't the cause. Only the runner may claim it aborted.
        SyncJobService.Started started = beginTestJob();
        service.start();

        service.executeBody(started, handle -> {
            service.stop(); // shutdown begins mid-body
            throw new IllegalStateException("vendor returned HTTP 500");
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(started.job().getErrorSummary()).contains("vendor returned HTTP 500");
    }

    @Test
    void executeBody_shutdownBeganBeforeBodyStarted_cancelsWithoutRunningIt() {
        SyncJobService.Started started = beginTestJob();
        AtomicBoolean bodyInvoked = new AtomicBoolean();
        service.stop();

        service.executeBody(started, handle -> bodyInvoked.set(true));

        assertThat(bodyInvoked).isFalse();
        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
    }

    @Test
    void requestCancelForTeardown_crashedJobWithStaleLease_reapsInlineAndReportsConnectionFree() {
        // The disconnect fence's no-wedge half: a job stranded RUNNING by a pod crash is reaped here,
        // so the admin's Disconnect succeeds as soon as the lease expires rather than 409ing until the
        // hourly sweep runs.
        SyncJob crashed = newJob(7L, SyncJobStatus.RUNNING);
        crashed.setHeartbeatAt(Instant.now().minus(Duration.ofMinutes(30)));
        when(syncJobRepository.findAbandonedForConnection(CONNECTION_ID, 900)).thenReturn(List.of(crashed));
        when(syncJobRepository.markAbandoned(7L, "Abandoned: no heartbeat (likely pod restart)", 900))
                .thenReturn(1);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.requestCancelForTeardown(CONNECTION_ID)).isEmpty();

        verify(syncJobRepository).markAbandoned(7L, "Abandoned: no heartbeat (likely pod restart)", 900);
    }

    @Test
    void requestCancelForTeardown_liveJob_requestsCancellationAndNamesItSoTheAdminCanRetry() {
        SyncJob live = newJob(8L, SyncJobStatus.RUNNING);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.of(live));
        when(syncJobRepository.markCancelRequested(8L, SyncJobStatus.ACTIVE)).thenReturn(1);

        assertThat(service.requestCancelForTeardown(CONNECTION_ID)).contains(8L);

        // Durable, so a runner on another pod picks it up on its next heartbeat pass — the 409 is a
        // "retry shortly", not a dead end.
        verify(syncJobRepository).markCancelRequested(8L, SyncJobStatus.ACTIVE);
    }

    @Test
    void requestCancelForTeardown_jobCompletedBetweenReadAndWrite_reportsConnectionFree() {
        SyncJob raced = newJob(8L, SyncJobStatus.RUNNING);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.of(raced));
        when(syncJobRepository.markCancelRequested(8L, SyncJobStatus.ACTIVE)).thenReturn(0);

        assertThat(service.requestCancelForTeardown(CONNECTION_ID)).isEmpty();
    }

    @Test
    void requestCancelForTeardown_runningLocally_reachesTheRunnerWithoutWaitingForTheHeartbeat() {
        SyncJobService.Started started = beginTestJob();
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        CONNECTION_ID, SyncJobStatus.ACTIVE))
                .thenReturn(Optional.of(started.job()));
        when(syncJobRepository.markCancelRequested(started.job().getId(), SyncJobStatus.ACTIVE))
                .thenReturn(1);

        service.executeBody(started, handle -> {
            service.requestCancelForTeardown(CONNECTION_ID);
            assertThat(handle.isCancellationRequested()).isTrue();
        });
    }

    @Test
    void executeBody_cancellationObservedByRunner_marksCancelled() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {
            started.handle().refreshCancellation(true);
            assertThat(handle.isCancellationRequested()).isTrue();
            handle.reportCancelled();
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
    }

    @Test
    void executeBody_cancellationSeenButBodyReturnsWithoutReporting_marksSucceeded() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {
            started.handle().refreshCancellation(true);
            assertThat(handle.isCancellationRequested()).isTrue();
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
    }

    /**
     * Interleaving A — the body wins the race: a cancel request commits (from another replica, or from
     * {@link SyncJobService#stop()}) while the body is finishing, and the body returns normally without
     * ever observing it. The work is done and the watermarks are advanced, so the outcome is SUCCEEDED:
     * recording CANCELLED here would hide a real success from {@code lastSuccessfulJob}.
     */
    @Test
    void executeBody_cancelRequestCommittedWhileBodyReturnedNormally_staysSucceeded() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {
            // Another replica flips the durable flag mid-body; this runner never polls it.
            started.job().setCancelRequested(true);
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
    }

    /**
     * Interleaving B — the cancel wins the race: the same durable flag is committed, but this time the
     * body observes it and aborts. The runner's own report is what makes it CANCELLED, so the
     * cross-replica cancel path still works end to end.
     */
    @Test
    void executeBody_cancelRequestCommittedAndBodyAborted_recordsCancelled() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {
            started.job().setCancelRequested(true);
            handle.refreshCancellation(true);
            assertThat(handle.isCancellationRequested()).isTrue();
            handle.reportCancelled();
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
    }

    @Test
    void executeBody_runnerReportsWarnings_marksSucceededWithWarnings() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, SyncJobHandle::reportWarnings);

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
    }

    @Test
    void executeBody_bodyThrows_marksFailedWithTruncatedErrorSummary() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(started, handle -> {
            throw new RuntimeException("boom");
        });

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(started.job().getErrorSummary()).contains("RuntimeException").contains("boom");
    }

    @Test
    void executeBody_progressReportedInBody_isFlushedOnTerminalWrite() {
        SyncJobService.Started started = beginTestJob();

        service.executeBody(
                started, handle -> handle.progress(4, 12, SyncProgress.of(SyncPhase.PULL_REQUESTS, "pull-requests")));

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(started.job().getItemsProcessed()).isEqualTo(4);
        assertThat(started.job().getItemsTotal()).isEqualTo(12);
        assertThat(started.job().getProgress()).containsEntry("currentStep", "pull-requests");
    }

    // The "a late/terminal write cannot resurrect or overwrite an already-terminal row" invariant is a
    // database compare-and-set guarantee (markRunning's `status = 'PENDING'` and completeActiveJob's
    // `status IN :activeStatuses` clauses). Proving it here would only exercise the in-memory fakes that
    // mirror those clauses, so it lives at the integration tier against real Postgres instead —
    // SyncJobActiveIndexIntegrationTest#lateCompletion_cannotOverwriteAlreadyTerminalRow and
    // #terminalTransition_isCompareAndSet_andPersistsProgressJson.

    /**
     * Mid-flight progress must refresh the resources pane too. Publishing RESOURCES only at terminal is
     * what made repo/issue/PR counts sit still for the whole run and then teleport to their final
     * numbers — the "updated all at once" the counts pane showed.
     */
    @Test
    void progressWrite_publishesResourcesScopeMidFlightAndNotOnlyAtCompletion() {
        SyncJobService.Started started = beginTestJob();
        List<SyncStateChangedEvent> published = new ArrayList<>();
        doAnswer(inv -> {
                    if (inv.getArgument(0) instanceof SyncStateChangedEvent e) {
                        published.add(e);
                    }
                    return null;
                })
                .when(eventPublisher)
                .publishEvent(any(Object.class));

        List<SyncStateChangedEvent.Scope> seenWhileTheJobWasStillRunning = new ArrayList<>();
        service.executeBody(started, handle -> {
            handle.progress(1, 10, SyncProgress.of(SyncPhase.ISSUES, "syncing issues"));
            // Snapshot INSIDE the body. completeJob publishes RESOURCES too, so asserting after
            // executeBody returns would pass even if a progress write published nothing — which is
            // precisely the bug: counts frozen for the whole run, then teleporting at the end.
            published.forEach(e -> seenWhileTheJobWasStillRunning.add(e.scope()));
        });

        assertThat(seenWhileTheJobWasStillRunning)
                .contains(SyncStateChangedEvent.Scope.RESOURCES)
                .contains(SyncStateChangedEvent.Scope.JOB);
    }

    /**
     * The trailing flush, end to end. A runner that reports a phase boundary and then goes quiet for
     * minutes must not leave the row showing the state before it — the sweep is what lands that last
     * suppressed update while the runner is still busy.
     */
    @Test
    void flushBufferedProgress_runningJobWithSuppressedUpdate_persistsItWhileTheBodyIsStillBusy() throws Exception {
        SyncJobService.Started started = beginTestJob();
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.execute(() -> service.executeBody(started, handle -> {
                handle.progress(1, 10, SyncProgress.of(SyncPhase.ISSUES, "written"));
                // Throttled — buffered only. The runner then goes quiet, as it would while grinding
                // through a slow phase.
                handle.progress(7, 10, SyncProgress.of(SyncPhase.ISSUES, "suppressed"));
                reported.countDown();
                try {
                    releaseBody.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(started.job().getItemsProcessed()).isEqualTo(1);

            // Advance past the throttle window instead of sleeping: the handle reads the same injected
            // clock, so the buffered "suppressed" update is now due for the trailing flush.
            clock.advance(
                    Duration.ofSeconds(SyncJobHandle.MIN_WRITE_INTERVAL_SECONDS).plusMillis(200));
            service.flushBufferedProgress();

            assertThat(started.job().getItemsProcessed()).isEqualTo(7);
            assertThat(started.job().getProgress()).containsEntry(SyncProgress.KEY_CURRENT_STEP, "suppressed");

            releaseBody.countDown();
        }
    }

    @Test
    void reapAbandonedJobs_findsAndFailsStaleRunningJobs() {
        SyncJob stale = newJob(8L, SyncJobStatus.RUNNING);
        stale.setHeartbeatAt(Instant.now().minus(Duration.ofMinutes(30)));
        when(syncJobRepository.findAbandoned(900)).thenReturn(List.of(stale));
        when(syncJobRepository.markAbandoned(8L, "Abandoned: no heartbeat (likely pod restart)", 900))
                .thenReturn(1);

        int reaped = service.reapAbandonedJobs();

        assertThat(reaped).isEqualTo(1);
        verify(syncJobRepository).markAbandoned(8L, "Abandoned: no heartbeat (likely pod restart)", 900);
        verify(eventPublisher).publishEvent(any(SyncStateChangedEvent.class));
        verify(syncJobRepository, never()).save(any());
    }

    @Test
    void reapAbandonedJobs_noStaleJobs_reapsNothing() {
        when(syncJobRepository.findAbandoned(anyLong())).thenReturn(List.of());

        assertThat(service.reapAbandonedJobs()).isZero();
    }

    @Test
    void reapAbandonedJobs_heartbeatRefreshedAfterCandidateRead_doesNotReapOrPublish() {
        SyncJob candidate = newJob(8L, SyncJobStatus.RUNNING);
        when(syncJobRepository.findAbandoned(900)).thenReturn(List.of(candidate));
        when(syncJobRepository.markAbandoned(8L, "Abandoned: no heartbeat (likely pod restart)", 900))
                .thenReturn(0);

        assertThat(service.reapAbandonedJobs()).isZero();

        verify(eventPublisher, never()).publishEvent(any(SyncStateChangedEvent.class));
    }

    @Test
    void reapAbandonedJobs_skipsLocallyOwnedJobEvenWithStaleLease() throws Exception {
        // A job owned by a live runner in THIS JVM is not abandoned — a stale lease only means the
        // heartbeat was briefly starved. The sweep must leave it alone (no cancel, no interrupt, no
        // terminal write); killing it would abort a healthy sync, e.g. behind a concurrent "Sync now".
        SyncJobService.Started started = beginTestJob();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(syncJobRepository.findAbandoned(900)).thenReturn(List.of(started.job()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> service.executeBody(started, handle -> {
                bodyEntered.countDown();
                try {
                    releaseBody.await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("locally-owned job was wrongly interrupted", e);
                }
            }));
            assertThat(bodyEntered.await(2, TimeUnit.SECONDS)).isTrue();

            try {
                assertThat(service.reapAbandonedJobs()).isZero();
                verify(syncJobRepository, never()).markAbandoned(anyLong(), any(), anyLong());
                verify(syncJobRepository, never()).markCancelRequested(anyLong(), any());
                assertThat(interrupted).isFalse();
            } finally {
                releaseBody.countDown();
                running.get(2, TimeUnit.SECONDS);
            }
        }

        assertThat(started.job().getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
    }

    @Test
    void refreshLeases_touchesHeartbeatAndRefreshesRegisteredHandlesCancelFlag() {
        SyncJobService.Started started = beginTestJob();
        long jobId = started.job().getId();
        SyncJobRepository.CancelFlagProjection projection = mock(SyncJobRepository.CancelFlagProjection.class);
        when(projection.getId()).thenReturn(jobId);
        when(projection.isCancelRequested()).thenReturn(true);
        when(syncJobRepository.findCancelFlags(List.of(jobId))).thenReturn(List.of(projection));

        // A handle is registered in the in-JVM registry only while executeBody is running, so drive the
        // heartbeat pass from inside the body — the one moment a genuinely-RUNNING job's lease exists.
        service.executeBody(started, handle -> service.refreshLeases());

        verify(syncJobRepository).touchHeartbeat(List.of(jobId));
        assertThat(started.handle().isCancellationRequested()).isTrue();
    }

    @Test
    void refreshLeases_staleFalseNeverClearsCancellation() {
        SyncJobService.Started started = beginTestJob();
        long jobId = started.job().getId();
        SyncJobRepository.CancelFlagProjection projection = mock(SyncJobRepository.CancelFlagProjection.class);
        when(projection.getId()).thenReturn(jobId);
        when(projection.isCancelRequested()).thenReturn(false);
        when(syncJobRepository.findCancelFlags(List.of(jobId))).thenReturn(List.of(projection));

        service.executeBody(started, handle -> {
            handle.refreshCancellation(true);

            service.refreshLeases();

            assertThat(handle.isCancellationRequested()).isTrue();
        });
    }

    @Test
    void refreshLeases_noActiveHandles_skipsWithoutTouchingRepository() {
        service.refreshLeases();

        verify(syncJobRepository, never()).touchHeartbeat(any());
    }

    @Test
    void requestCancel_pendingJob_flipsFlagViaTargetedUpdateWithoutTouchingAnyHandle() {
        SyncJobService.Started started = beginTestJob(); // PENDING: no in-JVM handle registered yet
        long jobId = started.job().getId();
        when(syncJobRepository.findByIdAndWorkspace_Id(jobId, WORKSPACE_ID)).thenReturn(Optional.of(started.job()));
        when(syncJobRepository.markCancelRequested(jobId, SyncJobStatus.ACTIVE)).thenReturn(1);

        service.requestCancel(WORKSPACE_ID, jobId);

        verify(syncJobRepository).markCancelRequested(jobId, SyncJobStatus.ACTIVE);
        assertThat(started.handle().isCancellationRequested()).isFalse();
    }

    @Test
    void requestCancel_runningJob_refreshesRegisteredLocalHandleImmediately() {
        SyncJobService.Started started = beginTestJob();
        long jobId = started.job().getId();
        when(syncJobRepository.findByIdAndWorkspace_Id(jobId, WORKSPACE_ID)).thenReturn(Optional.of(started.job()));
        when(syncJobRepository.markCancelRequested(jobId, SyncJobStatus.ACTIVE)).thenReturn(1);

        // The handle is registered only while executeBody runs; issue the cancel from inside the body so it
        // lands on the same JVM that's executing the job and the local handle refreshes without the sweep.
        service.executeBody(started, handle -> {
            service.requestCancel(WORKSPACE_ID, jobId);
            assertThat(handle.isCancellationRequested()).isTrue();
        });
    }

    @Test
    void requestCancel_terminalJob_throwsStateConflict() {
        SyncJob terminal = newJob(9L, SyncJobStatus.SUCCEEDED);
        when(syncJobRepository.findByIdAndWorkspace_Id(9L, WORKSPACE_ID)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service.requestCancel(WORKSPACE_ID, 9L))
                .isInstanceOf(SyncStateConflictException.class);
    }

    @Test
    void requestCancel_unknownJob_throwsEntityNotFound() {
        when(syncJobRepository.findByIdAndWorkspace_Id(999L, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestCancel(WORKSPACE_ID, 999L)).isInstanceOf(EntityNotFoundException.class);
    }

    private SyncJobService.Started beginTestJob() {
        return service.beginJob(defaultRequest());
    }

    private static SyncJobRequest defaultRequest() {
        return new SyncJobRequest(
                WORKSPACE_ID, CONNECTION_ID, IntegrationKind.GITHUB, SyncJobType.INITIAL, SyncJobTrigger.MANUAL, null);
    }
}
