package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivity;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit coverage for {@link SyncStatusService}: connection-health derivation across every
 * {@code IntegrationState} × job-outcome combination, the trigger idempotent-absorb path, the
 * not-ACTIVE / no-runner 409 paths, and the catalog join.
 */
class SyncStatusServiceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    /** A sibling connection in the SAME workspace — the cross-connection cancel scope check. */
    private static final long OTHER_CONNECTION_ID = 11L;

    private static final long JOB_ID = 555L;

    @Mock
    private ConnectionAdminService connectionAdminService;

    @Mock
    private SyncJobService syncJobService;

    @Mock
    private SyncJobRepository syncJobRepository;

    @Mock
    private ConnectionActivityRepository connectionActivityRepository;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private IntegrationManifestRegistry manifests;

    @Mock
    private ConnectionSyncStateProvider githubProvider;

    @Mock
    private IntegrationSyncRunner githubRunner;

    private Connection connection;
    private final SyncTargetProvider syncTargetProvider = mock(SyncTargetProvider.class);
    private SyncStatusService service;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of()));
        setConnectionId(connection, CONNECTION_ID);
        connection.setState(IntegrationState.ACTIVE);

        lenient()
                .when(connectionAdminService.findInWorkspaceOrThrow(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(connection);
        lenient().when(githubProvider.kind()).thenReturn(IntegrationKind.GITHUB);
        lenient().when(githubProvider.describe(any(), anyLong())).thenReturn(ConnectionSyncDetails.empty());
        lenient().when(githubProvider.resources(any(), anyLong())).thenReturn(List.of());
        lenient().when(githubRunner.kind()).thenReturn(IntegrationKind.GITHUB);

        lenient()
                .when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        lenient()
                .when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        lenient().when(connectionActivityRepository.findById(anyLong())).thenReturn(Optional.empty());

        service = new SyncStatusService(
                connectionAdminService,
                syncJobService,
                syncJobRepository,
                connectionActivityRepository,
                taskExecutor,
                List.of(githubProvider),
                List.of(githubRunner),
                syncTargetProvider);
    }

    @Test
    void getStatus_pendingConnection_healthIsPending() {
        connection.setState(IntegrationState.PENDING);

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.PENDING);
    }

    @Test
    void getStatus_suspendedConnection_healthIsSuspended() {
        connection.setState(IntegrationState.SUSPENDED);

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.SUSPENDED);
    }

    @Test
    void getStatus_activeNoJobsNoResources_healthIsHealthy() {
        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.HEALTHY);
    }

    @Test
    void getStatus_activeLastFinishedJobFailed_healthIsFailed() {
        SyncJob failed = terminalJob(SyncJobStatus.FAILED);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
                        CONNECTION_ID, SyncJobStatus.TERMINAL))
                .thenReturn(Optional.of(failed));

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.FAILED);
    }

    @Test
    void getStatus_activeLastFinishedJobSucceededWithWarnings_healthIsDegraded() {
        SyncJob warned = terminalJob(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        when(syncJobRepository.findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
                        CONNECTION_ID, SyncJobStatus.TERMINAL))
                .thenReturn(Optional.of(warned));

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.DEGRADED);
    }

    private static SyncResourceState resource(long id, @Nullable Instant lastSyncedAt, @Nullable String lastError) {
        return new SyncResourceState(
                id,
                "owner/repo" + id,
                "repo" + id,
                SyncResourceState.Type.REPOSITORY,
                lastSyncedAt == null ? SyncResourceState.STATE_PENDING : SyncResourceState.STATE_SYNCED,
                lastSyncedAt,
                0L,
                List.of(),
                null,
                lastError,
                null,
                null);
    }

    /** The overview's honest headline: "3 stale · 1 never synced · 2 errored of 42". */
    @Nested
    class ResourceCountsRollup {

        private void withResources(List<SyncResourceState> resources, @Nullable Duration cadence) {
            when(githubProvider.resources(any(), anyLong())).thenReturn(resources);
            when(githubProvider.describe(any(), anyLong()))
                    .thenReturn(new ConnectionSyncDetails(null, null, cadence, null, null, false));
        }

        @Test
        void resourceNeverSynced_countsAsPending() {
            Instant fresh = Instant.now();
            withResources(
                    List.of(resource(1L, fresh, null), resource(2L, null, null), resource(3L, null, null)),
                    Duration.ofHours(1));

            var counts = service.getStatus(WORKSPACE_ID, CONNECTION_ID).resourceCounts();

            // Defined on the absence of a timestamp, not on a provider's status vocabulary — so it means
            // the same thing for a repository, a Slack channel and an Outline collection.
            assertThat(counts.pending()).isEqualTo(2L);
            assertThat(counts.total()).isEqualTo(3L);
        }

        @Test
        void lastSyncOlderThanTwiceTheCadence_countsAsStale() {
            Instant fresh = Instant.now().minus(Duration.ofMinutes(30));
            Instant old = Instant.now().minus(Duration.ofHours(5));
            withResources(List.of(resource(1L, fresh, null), resource(2L, old, null)), Duration.ofHours(1));

            var counts = service.getStatus(WORKSPACE_ID, CONNECTION_ID).resourceCounts();

            assertThat(counts.stale()).isEqualTo(1L);
        }

        @Test
        void lastSyncWithinTwiceTheCadence_isNotStale() {
            // A resource is legitimately "one cadence old" for the whole interval between two runs, so
            // flagging at 1x would show the entire fleet stale right before every scheduled sync.
            Instant justOverOneCadence = Instant.now().minus(Duration.ofMinutes(90));
            withResources(List.of(resource(1L, justOverOneCadence, null)), Duration.ofHours(1));

            assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID)
                            .resourceCounts()
                            .stale())
                    .isZero();
        }

        @Test
        void unknownCadence_declinesToJudgeStalenessRatherThanGuessing() {
            Instant ancient = Instant.now().minus(Duration.ofDays(400));
            withResources(List.of(resource(1L, ancient, null)), null);

            // Without a known cron there is no yardstick; inventing a default would either flag healthy
            // resources or hide real ones, and the UI must simply not make the claim.
            assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID)
                            .resourceCounts()
                            .stale())
                    .isZero();
        }

        @Test
        void neverSyncedResource_isPendingButNotAlsoCountedStale() {
            withResources(List.of(resource(1L, null, null)), Duration.ofHours(1));

            var counts = service.getStatus(WORKSPACE_ID, CONNECTION_ID).resourceCounts();

            // "Never synced" and "stale" are different diagnoses and the overview says both; a null
            // timestamp must not be read as infinitely old.
            assertThat(counts.pending()).isEqualTo(1L);
            assertThat(counts.stale()).isZero();
        }

        @Test
        void erroredAndStaleResource_isCountedInBothWithoutDoubleCountingTheTotal() {
            Instant old = Instant.now().minus(Duration.ofHours(5));
            withResources(List.of(resource(1L, old, "boom")), Duration.ofHours(1));

            var counts = service.getStatus(WORKSPACE_ID, CONNECTION_ID).resourceCounts();

            // The subsets deliberately overlap — they answer different questions and must never be summed.
            assertThat(counts.errored()).isEqualTo(1L);
            assertThat(counts.stale()).isEqualTo(1L);
            assertThat(counts.total()).isEqualTo(1L);
        }

        @Test
        void noResources_reportsAllZeroRatherThanFailing() {
            withResources(List.of(), Duration.ofHours(1));

            var counts = service.getStatus(WORKSPACE_ID, CONNECTION_ID).resourceCounts();

            assertThat(counts.total()).isZero();
            assertThat(counts.pending()).isZero();
            assertThat(counts.stale()).isZero();
            assertThat(counts.errored()).isZero();
        }
    }

    @Test
    void getStatus_erroredResourcePresent_healthIsDegraded() {
        when(githubProvider.resources(any(), anyLong()))
                .thenReturn(List.of(new SyncResourceState(
                        1L,
                        "owner/repo",
                        "repo",
                        SyncResourceState.Type.REPOSITORY,
                        "ERROR",
                        null,
                        null,
                        List.of(),
                        null,
                        "boom",
                        null,
                        null)));

        var status = service.getStatus(WORKSPACE_ID, CONNECTION_ID);

        assertThat(status.health()).isEqualTo(ConnectionHealth.DEGRADED);
        assertThat(status.resourceCounts().total()).isEqualTo(1L);
        assertThat(status.resourceCounts().errored()).isEqualTo(1L);
    }

    @Test
    void getStatus_vendorHealthDegraded_healthIsDegraded() {
        when(githubProvider.describe(any(), anyLong()))
                .thenReturn(new ConnectionSyncDetails(null, null, null, null, null, true));

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).health()).isEqualTo(ConnectionHealth.DEGRADED);
    }

    @Test
    void getStatus_noProviderRegistered_gracefullyDegradesToEmptyDetails() {
        SyncStatusService noProviders = new SyncStatusService(
                connectionAdminService,
                syncJobService,
                syncJobRepository,
                connectionActivityRepository,
                taskExecutor,
                List.of(),
                List.of(),
                mock(SyncTargetProvider.class));

        var status = noProviders.getStatus(WORKSPACE_ID, CONNECTION_ID);

        assertThat(status.health()).isEqualTo(ConnectionHealth.HEALTHY);
        assertThat(status.webhookRegistered()).isNull();
        assertThat(status.rateLimit()).isNull();
        assertThat(status.resourceCounts().total()).isZero();
    }

    @Test
    void getStatus_activityRecorded_includesLastEventProcessedAtFromActivityRepository_notProvider() {
        Instant lastEventAt = Instant.parse("2026-07-14T10:00:00Z");
        when(connectionActivityRepository.findById(CONNECTION_ID))
                .thenReturn(Optional.of(new ConnectionActivity(CONNECTION_ID, WORKSPACE_ID, lastEventAt, "push")));

        var status = service.getStatus(WORKSPACE_ID, CONNECTION_ID);

        assertThat(status.lastEventProcessedAt()).isEqualTo(lastEventAt);
    }

    @Test
    void getStatus_noActivityRecorded_lastEventProcessedAtIsNull() {
        var status = service.getStatus(WORKSPACE_ID, CONNECTION_ID);

        assertThat(status.lastEventProcessedAt()).isNull();
    }

    // backfillSupported is what the admin UI gates its "Backfill" button on, so it must track the
    // runner's own answer rather than the connection's kind — otherwise the button and triggerSync's
    // 409 disagree.

    @Test
    void getStatus_runnerSupportsBackfill_backfillSupportedIsTrue() {
        when(githubRunner.supportsBackfill()).thenReturn(true);

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).backfillSupported())
                .isTrue();
    }

    @Test
    void getStatus_runnerDoesNotSupportBackfill_backfillSupportedIsFalse() {
        when(githubRunner.supportsBackfill()).thenReturn(false);

        assertThat(service.getStatus(WORKSPACE_ID, CONNECTION_ID).backfillSupported())
                .isFalse();
    }

    @Test
    void getStatus_noRunnerRegisteredForKind_backfillSupportedIsFalse() {
        SyncStatusService noRunners = new SyncStatusService(
                connectionAdminService,
                syncJobService,
                syncJobRepository,
                connectionActivityRepository,
                taskExecutor,
                List.of(githubProvider),
                List.of(),
                mock(SyncTargetProvider.class));

        assertThat(noRunners.getStatus(WORKSPACE_ID, CONNECTION_ID).backfillSupported())
                .isFalse();
    }

    @Test
    void triggerSync_notActiveConnection_throwsStateConflict() {
        connection.setState(IntegrationState.SUSPENDED);

        assertThatThrownBy(() -> service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, null))
                .isInstanceOf(SyncStateConflictException.class);
        verify(syncJobService, never()).beginJob(any());
    }

    @Test
    void triggerSync_noRunnerForKind_throwsSyncNotSupported() {
        SyncStatusService noRunners = new SyncStatusService(
                connectionAdminService,
                syncJobService,
                syncJobRepository,
                connectionActivityRepository,
                taskExecutor,
                List.of(githubProvider),
                List.of(),
                mock(SyncTargetProvider.class));

        assertThatThrownBy(() -> noRunners.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, null))
                .isInstanceOf(SyncNotSupportedException.class);
    }

    @Test
    void triggerSync_backfillUnsupportedByRunner_throwsSyncNotSupported() {
        when(githubRunner.supportsBackfill()).thenReturn(false);

        assertThatThrownBy(() -> service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.BACKFILL, null))
                .isInstanceOf(SyncNotSupportedException.class);
    }

    @Test
    void triggerSync_initialType_throwsStateConflictAndRecordsNoJob() {
        // INITIAL is lifecycle-owned: a manual INITIAL on a mature connection would run a full sync that
        // silently skips the deletion sweep and record a bogus INITIAL/MANUAL job. Only RECONCILIATION
        // and BACKFILL are client-triggerable — reject INITIAL before any job row is begun.
        assertThatThrownBy(() -> service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.INITIAL, null))
                .isInstanceOf(SyncStateConflictException.class);

        verify(syncJobService, never()).beginJob(any());
    }

    @Test
    void triggerSync_newJob_dispatchesAsyncAndReturnsCreatedTrue() {
        SyncJob created = pendingJob();
        SyncJobService.Started started = new SyncJobService.Started(created, mock(SyncJobHandle.class));
        when(syncJobService.beginJob(new SyncJobRequest(
                        WORKSPACE_ID,
                        CONNECTION_ID,
                        IntegrationKind.GITHUB,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.MANUAL,
                        42L)))
                .thenReturn(started);

        SyncStatusService.TriggerOutcome outcome =
                service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, 42L);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.job().id()).isEqualTo(created.getId());
        verify(taskExecutor).execute(any());
    }

    @Test
    void shouldPermitImmediateRepositoryRecheckWhenAdminStartsConnectionSync() {
        var started = new SyncJobService.Started(pendingJob(), mock(SyncJobHandle.class));
        when(syncJobService.beginJob(any())).thenReturn(started);
        doAnswer(invocation -> {
                    Runnable body = invocation.getArgument(0);
                    body.run();
                    return null;
                })
                .when(taskExecutor)
                .execute(any());
        doAnswer(invocation -> {
                    Consumer<SyncExecutionHandle> body = invocation.getArgument(1);
                    body.accept(started.handle());
                    return null;
                })
                .when(syncJobService)
                .executeBody(eq(started), any());
        service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, null);
        var order = inOrder(syncTargetProvider, githubRunner);
        order.verify(syncTargetProvider).recheckUnavailableRepositories(WORKSPACE_ID);
        order.verify(githubRunner).reconcile(any(), any(), eq(SyncJobType.RECONCILIATION));
    }

    @Test
    void triggerSync_duplicateActiveJob_returnsCreatedFalseWithActiveJob() {
        SyncJob active = pendingJob();
        when(syncJobService.beginJob(new SyncJobRequest(
                        WORKSPACE_ID,
                        CONNECTION_ID,
                        IntegrationKind.GITHUB,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.MANUAL,
                        null)))
                .thenThrow(new SyncJobConflictException(active));

        SyncStatusService.TriggerOutcome outcome =
                service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, null);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.job().id()).isEqualTo(active.getId());
        verify(taskExecutor, never()).execute(any());
    }

    @Test
    void triggerSync_differentTypeAlreadyRunning_throwsStateConflictNotAbsorb() {
        // A RECONCILIATION is already in flight; the caller asks for a BACKFILL. Absorbing (silently
        // returning the reconciliation) would drop the caller's request — it must be a 409, not a 200.
        when(githubRunner.supportsBackfill()).thenReturn(true);
        SyncJob reconciling = pendingJob(); // type == RECONCILIATION
        when(syncJobService.beginJob(any())).thenThrow(new SyncJobConflictException(reconciling));

        assertThatThrownBy(() -> service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.BACKFILL, null))
                .isInstanceOf(SyncStateConflictException.class);
        verify(taskExecutor, never()).execute(any());
    }

    @Test
    void triggerSync_executorRejectsDispatch_finalizesRowViaFailStartedAndRethrows() {
        // The job row is created synchronously, but the bounded executor is saturated and rejects the
        // async body. The row must be finalized (failStarted, so it doesn't hold the one-active slot)
        // and the TaskRejectedException must propagate so the controller maps it to 503 — not a 500.
        SyncJob created = pendingJob();
        SyncJobService.Started started = new SyncJobService.Started(created, mock(SyncJobHandle.class));
        when(syncJobService.beginJob(any())).thenReturn(started);
        doThrow(new TaskRejectedException("executor saturated"))
                .when(taskExecutor)
                .execute(any());

        assertThatThrownBy(() -> service.triggerSync(WORKSPACE_ID, CONNECTION_ID, SyncJobType.RECONCILIATION, null))
                .isInstanceOf(TaskRejectedException.class);

        verify(syncJobService).failStarted(eq(started), any());
    }

    @Test
    @DisplayName(
            "cancelling a job that belongs to a SIBLING connection in the same workspace must 404 without cancelling")
    void cancelJob_jobBelongsToDifferentConnection_throwsEntityNotFoundAndNeverCancels() {
        // The (workspace, connection) scope check is a security control: a job id that exists and is
        // readable in this workspace, but hangs off a DIFFERENT connection, must not have its cancel
        // flag flipped through the path connection. `never(requestCancel)` is the assertion that dies
        // if the ownership `.filter(...)` is dropped — the trailing re-read would 404 regardless.
        SyncJob siblingConnectionJob = jobOnConnection(OTHER_CONNECTION_ID);
        when(syncJobRepository.findByIdAndWorkspace_Id(JOB_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(siblingConnectionJob));

        assertThatThrownBy(() -> service.cancelJob(WORKSPACE_ID, CONNECTION_ID, JOB_ID))
                .isInstanceOf(EntityNotFoundException.class);

        verify(syncJobService, never()).requestCancel(anyLong(), anyLong());
    }

    @Test
    @DisplayName("cancelling a job that exists but lives in another workspace must 404 without cancelling")
    void cancelJob_jobNotInWorkspace_throwsEntityNotFoundAndNeverCancels() {
        // The job row exists globally (findById would find it) but is not visible in this workspace.
        // Stubbing the unscoped findById proves the workspace predicate — not mere absence — is what
        // rejects the request: swapping findByIdAndWorkspace_Id for findById makes this test go red.
        when(syncJobRepository.findByIdAndWorkspace_Id(JOB_ID, WORKSPACE_ID)).thenReturn(Optional.empty());
        lenient().when(syncJobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobOnConnection(CONNECTION_ID)));

        assertThatThrownBy(() -> service.cancelJob(WORKSPACE_ID, CONNECTION_ID, JOB_ID))
                .isInstanceOf(EntityNotFoundException.class);

        verify(syncJobService, never()).requestCancel(anyLong(), anyLong());
    }

    @Test
    void cancelJob_ownJobInWorkspace_requestsCancelAndReturnsRereadJob() {
        SyncJob job = jobOnConnection(CONNECTION_ID);
        when(syncJobRepository.findByIdAndWorkspace_Id(JOB_ID, WORKSPACE_ID)).thenReturn(Optional.of(job));
        when(syncJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        SyncJobDTO dto = service.cancelJob(WORKSPACE_ID, CONNECTION_ID, JOB_ID);

        assertThat(dto.id()).isEqualTo(JOB_ID);
        verify(syncJobService).requestCancel(WORKSPACE_ID, JOB_ID);
    }

    @Test
    void cancelJob_terminalJob_propagatesStateConflictFromEngine() {
        SyncJob job = jobOnConnection(CONNECTION_ID);
        when(syncJobRepository.findByIdAndWorkspace_Id(JOB_ID, WORKSPACE_ID)).thenReturn(Optional.of(job));
        doThrow(new SyncStateConflictException("already terminal", Map.of()))
                .when(syncJobService)
                .requestCancel(WORKSPACE_ID, JOB_ID);

        assertThatThrownBy(() -> service.cancelJob(WORKSPACE_ID, CONNECTION_ID, JOB_ID))
                .isInstanceOf(SyncStateConflictException.class);
    }

    @Test
    void catalog_joinsManifestRegistryWithExistingConnections() {
        when(connectionAdminService.manifests()).thenReturn(manifests);
        when(manifests.registeredKinds()).thenReturn(Set.of(IntegrationKind.GITHUB, IntegrationKind.SLACK));
        when(manifests.manifestFor(any())).thenReturn(Optional.empty());
        when(connectionAdminService.listForWorkspace(WORKSPACE_ID)).thenReturn(List.of(connection));

        List<IntegrationCatalogEntryDTO> catalog = service.catalog(WORKSPACE_ID);

        assertThat(catalog).hasSize(2);
        IntegrationCatalogEntryDTO githubEntry = catalog.stream()
                .filter(e -> e.kind() == IntegrationKind.GITHUB)
                .findFirst()
                .orElseThrow();
        assertThat(githubEntry.connected()).isTrue();
        assertThat(githubEntry.connectionId()).isEqualTo(CONNECTION_ID);

        IntegrationCatalogEntryDTO slackEntry = catalog.stream()
                .filter(e -> e.kind() == IntegrationKind.SLACK)
                .findFirst()
                .orElseThrow();
        assertThat(slackEntry.connected()).isFalse();
        assertThat(slackEntry.connectionId()).isNull();
    }

    @Test
    void catalog_exposesOnlyTheWorkspaceScmProvider() {
        when(connectionAdminService.manifests()).thenReturn(manifests);
        when(manifests.registeredKinds())
                .thenReturn(Set.of(IntegrationKind.GITHUB, IntegrationKind.GITLAB, IntegrationKind.SLACK));
        when(manifests.manifestFor(any())).thenReturn(Optional.empty());
        when(connectionAdminService.listForWorkspace(WORKSPACE_ID)).thenReturn(List.of(connection));

        assertThat(service.catalog(WORKSPACE_ID))
                .extracting(IntegrationCatalogEntryDTO::kind)
                .containsExactly(IntegrationKind.GITHUB, IntegrationKind.SLACK);
    }

    private SyncJob terminalJob(SyncJobStatus status) {
        SyncJob job = pendingJob();
        job.setStatus(status);
        job.setFinishedAt(Instant.now());
        return job;
    }

    private SyncJob pendingJob() {
        Workspace ws = new Workspace();
        ws.setId(WORKSPACE_ID);
        SyncJob job = new SyncJob(
                ws, connection, IntegrationKind.GITHUB, SyncJobType.RECONCILIATION, SyncJobTrigger.MANUAL, null);
        job.setId(JOB_ID);
        job.setCreatedAt(Instant.now());
        return job;
    }

    /** A {@link #JOB_ID} job hanging off an arbitrary connection id, for the cancel scope checks. */
    private SyncJob jobOnConnection(long connectionId) {
        Workspace ws = new Workspace();
        ws.setId(WORKSPACE_ID);
        Connection owner = new Connection(
                ws,
                IntegrationKind.GITHUB,
                String.valueOf(connectionId),
                new ConnectionConfig.GitHubAppConfig(connectionId, "acme", null, Set.of()));
        setConnectionId(owner, connectionId);
        SyncJob job =
                new SyncJob(ws, owner, IntegrationKind.GITHUB, SyncJobType.RECONCILIATION, SyncJobTrigger.MANUAL, null);
        job.setId(JOB_ID);
        job.setCreatedAt(Instant.now());
        return job;
    }

    private static void setConnectionId(Connection connection, long id) {
        ReflectionTestUtils.setField(connection, "id", id);
    }
}
