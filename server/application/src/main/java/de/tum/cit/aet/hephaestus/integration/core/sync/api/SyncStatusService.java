package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationFamily;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryAvailabilityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivity;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Application service for sync status, catalog, and manual job control. */
@ConditionalOnServerRole
@Service
public class SyncStatusService {

    private final ConnectionAdminService connectionAdminService;
    private final RepositoryAvailabilityProvider repositoryAvailabilityProvider;
    private final SyncJobService syncJobService;
    private final SyncJobRepository syncJobRepository;
    private final ConnectionActivityRepository connectionActivityRepository;
    private final AsyncTaskExecutor taskExecutor;
    private final List<ConnectionSyncStateProvider> providers;
    private final List<IntegrationSyncRunner> runners;

    public SyncStatusService(
            ConnectionAdminService connectionAdminService,
            SyncJobService syncJobService,
            SyncJobRepository syncJobRepository,
            ConnectionActivityRepository connectionActivityRepository,
            @Qualifier("syncJobExecutor") AsyncTaskExecutor taskExecutor,
            List<ConnectionSyncStateProvider> providers,
            List<IntegrationSyncRunner> runners,
            RepositoryAvailabilityProvider repositoryAvailabilityProvider) {
        this.repositoryAvailabilityProvider = repositoryAvailabilityProvider;
        this.connectionAdminService = connectionAdminService;
        this.syncJobService = syncJobService;
        this.syncJobRepository = syncJobRepository;
        this.connectionActivityRepository = connectionActivityRepository;
        this.taskExecutor = taskExecutor;
        this.providers = List.copyOf(providers);
        this.runners = List.copyOf(runners);
    }

    private @Nullable ConnectionSyncStateProvider providerFor(IntegrationKind kind) {
        return resolveSingle(providers, ConnectionSyncStateProvider::kind, kind);
    }

    private @Nullable IntegrationSyncRunner runnerFor(IntegrationKind kind) {
        return resolveSingle(runners, IntegrationSyncRunner::kind, kind);
    }

    private static <T> @Nullable T resolveSingle(
            List<T> beans, Function<T, IntegrationKind> kindOf, IntegrationKind kind) {
        T match = null;
        for (T bean : beans) {
            if (kindOf.apply(bean) == kind) {
                if (match != null) {
                    rejectDuplicate(match, bean);
                }
                match = bean;
            }
        }
        return match;
    }

    public ConnectionSyncStatusDTO getStatus(long workspaceId, long connectionId) {
        Connection connection = connectionAdminService.findInWorkspaceOrThrow(workspaceId, connectionId);
        ConnectionSyncStateProvider provider = providerFor(connection.getKind());
        IntegrationRef ref = connection.toRef();
        // Same predicate triggerSync guards BACKFILL with, so the UI offers the action exactly when the
        // request would be accepted. No runner registered for this kind → no backfill.
        IntegrationSyncRunner runner = runnerFor(connection.getKind());
        boolean backfillSupported = runner != null && runner.supportsBackfill();
        ConnectionSyncDetails providerDetails =
                provider == null ? ConnectionSyncDetails.empty() : provider.describe(ref, connectionId);
        Optional<ConnectionActivity> activity = connectionActivityRepository.findById(connectionId);
        List<SyncResourceState> resources = provider == null ? List.of() : provider.resources(ref, connectionId);

        Optional<SyncJob> activeJob = syncJobRepository.findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                connectionId, SyncJobStatus.ACTIVE);
        Optional<SyncJob> lastFinishedJob = syncJobRepository.findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
                connectionId, SyncJobStatus.TERMINAL);
        Optional<SyncJob> lastSuccessfulJob =
                syncJobRepository.findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
                        connectionId, SyncJobStatus.SUCCESSFUL);

        long erroredResources =
                resources.stream().filter(r -> r.lastError() != null).count();
        ResourceCountsDTO resourceCounts = rollUp(resources, erroredResources, providerDetails.syncInterval());
        ConnectionHealth health =
                deriveHealth(connection, lastFinishedJob, erroredResources, providerDetails.vendorHealthDegraded());

        return new ConnectionSyncStatusDTO(
                connection.getId(),
                connection.getKind(),
                connection.getState(),
                health,
                lastSuccessfulJob.map(SyncJob::getFinishedAt).orElse(null),
                activeJob.map(SyncJobDTO::from).orElse(null),
                lastFinishedJob.map(SyncJobDTO::from).orElse(null),
                providerDetails.nextScheduledSyncAt(),
                // The same cadence the stale rollup above judges against. Sent so the client can make the
                // per-resource judgement the rollup only makes in aggregate; null stays null, so a client
                // that can't know the cadence declines to judge rather than guessing one.
                providerDetails.syncInterval() == null
                        ? null
                        : providerDetails.syncInterval().toSeconds(),
                providerDetails.webhookRegistered(),
                activity.map(ConnectionActivity::getLastEventAt).orElse(null),
                providerDetails.rateLimit() == null ? null : RateLimitSnapshotDTO.from(providerDetails.rateLimit()),
                backfillSupported,
                providerDetails.backfill() == null ? null : BackfillSummaryDTO.from(providerDetails.backfill()),
                resourceCounts);
    }

    /**
     * Multiplier on the scheduled cadence past which a resource counts as stale. One cadence is not
     * enough — a resource is legitimately "one cadence old" for the entire interval between two runs,
     * so flagging at 1x would show the whole fleet stale right before every scheduled sync. Two means a
     * resource has to have missed a full run to be called out.
     */
    private static final int STALE_CADENCE_MULTIPLE = 2;

    /**
     * The overview's honest headline. Note the three subsets overlap (an errored resource is usually
     * also stale) — they are counted independently and must not be summed into "total".
     */
    private static ResourceCountsDTO rollUp(
            List<SyncResourceState> resources, long erroredResources, @Nullable Duration syncInterval) {
        long pending = resources.stream().filter(r -> r.lastSyncedAt() == null).count();

        // No known cadence → no staleness judgement. Guessing a default would either flag healthy
        // resources or hide real ones, and both are worse than the UI simply not making the claim.
        long stale = 0;
        if (syncInterval != null && !syncInterval.isZero() && !syncInterval.isNegative()) {
            Instant staleBefore = Instant.now().minus(syncInterval.multipliedBy(STALE_CADENCE_MULTIPLE));
            stale = resources.stream()
                    .filter(r -> r.lastSyncedAt() != null && r.lastSyncedAt().isBefore(staleBefore))
                    .count();
        }

        return new ResourceCountsDTO((long) resources.size(), erroredResources, pending, stale);
    }

    public List<SyncResourceStateDTO> getResources(Long workspaceId, long connectionId) {
        Connection connection = connectionAdminService.findInWorkspaceOrThrow(workspaceId, connectionId);
        ConnectionSyncStateProvider provider = providerFor(connection.getKind());
        if (provider == null) {
            return List.of();
        }
        return provider.resources(connection.toRef(), connectionId).stream()
                .map(SyncResourceStateDTO::from)
                .toList();
    }

    public Page<SyncJobDTO> getJobs(Long workspaceId, long connectionId, Pageable pageable) {
        connectionAdminService.findInWorkspaceOrThrow(workspaceId, connectionId);
        return syncJobRepository
                .findByConnection_IdAndWorkspace_Id(connectionId, workspaceId, pageable)
                .map(SyncJobDTO::from);
    }

    public record TriggerOutcome(SyncJobDTO job, boolean created) {}

    /**
     * Trigger a manual sync. Creates the job row synchronously (so a duplicate-active conflict can be
     * answered immediately) and dispatches the runner body on the dedicated no-queue sync executor.
     *
     * @throws SyncStateConflictException if the connection is not ACTIVE (409)
     * @throws SyncNotSupportedException  if no runner is registered for this kind, or the kind's runner
     *                                     doesn't support {@code BACKFILL} (409)
     */
    public TriggerOutcome triggerSync(
            long workspaceId, long connectionId, SyncJobType type, @Nullable Long triggeredByUserId) {
        Connection connection = connectionAdminService.findInWorkspaceOrThrow(workspaceId, connectionId);
        if (connection.getState() != IntegrationState.ACTIVE) {
            throw new SyncStateConflictException(
                    "Cannot trigger sync: connection " + connectionId
                            + " is not ACTIVE (state="
                            + connection.getState()
                            + ")",
                    Map.of("connectionId", connectionId, "connectionState", connection.getState()));
        }
        // Only RECONCILIATION and BACKFILL are client-triggerable. INITIAL is lifecycle-owned: it runs
        // once when a Connection goes ACTIVE and deliberately skips the deletion sweep (a mirror being
        // populated has nothing stale in it). A manual INITIAL on a mature connection would run a full
        // sync that silently omits the sweep AND record a bogus INITIAL/MANUAL job row, so reject it —
        // as an allowlist, so any future non-client type is rejected by default rather than by omission.
        if (type != SyncJobType.RECONCILIATION && type != SyncJobType.BACKFILL) {
            throw new SyncStateConflictException(
                    "Cannot trigger a " + type + " sync: only RECONCILIATION and BACKFILL are client-triggerable",
                    Map.of("requestedType", type));
        }

        IntegrationSyncRunner runner = runnerFor(connection.getKind());
        if (runner == null || (type == SyncJobType.BACKFILL && !runner.supportsBackfill())) {
            throw new SyncNotSupportedException(connection.getKind());
        }

        IntegrationRef ref = connection.toRef();
        try {
            SyncJobService.Started started = syncJobService.beginJob(new SyncJobRequest(
                    workspaceId, connectionId, connection.getKind(), type, SyncJobTrigger.MANUAL, triggeredByUserId));
            try {
                taskExecutor.execute(() -> syncJobService.executeBody(started, handle -> {
                    if (type == SyncJobType.RECONCILIATION
                            && (connection.getKind() == IntegrationKind.GITHUB
                                    || connection.getKind() == IntegrationKind.GITLAB)) {
                        repositoryAvailabilityProvider.recheckUnavailableRepositories(workspaceId);
                    }
                    if (type == SyncJobType.BACKFILL) {
                        runner.backfill(ref, handle);
                    } else {
                        runner.reconcile(ref, handle, type);
                    }
                }));
            } catch (TaskRejectedException e) {
                // The row was created but no body will run — finalize it so it doesn't hold the slot.
                syncJobService.failStarted(started, "Sync dispatch rejected (executor saturated)");
                throw e;
            }
            return new TriggerOutcome(SyncJobDTO.from(started.job()), true);
        } catch (SyncJobConflictException e) {
            // Absorb only a same-type duplicate (idempotent double-click). A different type in flight is a
            // genuine conflict — silently returning the running job would drop the caller's request.
            if (e.activeJob().getType() != type) {
                SyncJob active = e.activeJob();
                throw new SyncStateConflictException(
                        "Cannot start " + type
                                + " sync: a "
                                + active.getType()
                                + " sync is already running for connection "
                                + connectionId,
                        Map.of(
                                "conflictingJobId",
                                active.getId(),
                                "conflictingJobType",
                                active.getType(),
                                "conflictingJobStatus",
                                active.getStatus()),
                        e);
            }
            return new TriggerOutcome(SyncJobDTO.from(e.activeJob()), false);
        }
    }

    /**
     * @throws EntityNotFoundException     if the job doesn't exist in this workspace, or belongs to a
     *                                     different connection than the path's {@code connectionId}
     * @throws SyncStateConflictException if the job is already terminal (409)
     */
    public SyncJobDTO cancelJob(long workspaceId, long connectionId, long jobId) {
        // Validate the (workspace, connection) scope BEFORE mutating anything — a job id that exists
        // but belongs to a different connection must 404, not have its cancel flag flipped first.
        boolean jobInScope = syncJobRepository
                .findByIdAndWorkspace_Id(jobId, workspaceId)
                .filter(j -> j.getConnection().getId() == connectionId)
                .isPresent();
        if (!jobInScope) {
            throw new EntityNotFoundException("SyncJob", jobId);
        }

        syncJobService.requestCancel(workspaceId, jobId);

        SyncJob job =
                syncJobRepository.findById(jobId).orElseThrow(() -> new EntityNotFoundException("SyncJob", jobId));
        return SyncJobDTO.from(job);
    }

    public List<IntegrationCatalogEntryDTO> catalog(long workspaceId) {
        Map<IntegrationKind, Connection> byKind = new EnumMap<>(IntegrationKind.class);
        List<Connection> connections = connectionAdminService.listForWorkspace(workspaceId);
        for (Connection c : connections) {
            byKind.merge(c.getKind(), c, SyncStatusService::preferred);
        }

        // A workspace runs at most one SCM at a time, so once one is LIVE we hide the sibling SCM kind.
        // But an UNINSTALLED SCM must NOT keep hiding the alternative — otherwise the admin can never
        // switch vendors after disconnecting one. Only an active (non-UNINSTALLED) SCM narrows the list.
        IntegrationKind activeScm = connections.stream()
                .filter(c ->
                        c.getKind().family() == IntegrationFamily.SCM && c.getState() != IntegrationState.UNINSTALLED)
                .map(Connection::getKind)
                .findFirst()
                .orElse(null);

        IntegrationManifestRegistry manifests = connectionAdminService.manifests();
        return manifests.registeredKinds().stream()
                .sorted()
                .filter(kind -> activeScm == null || kind.family() != IntegrationFamily.SCM || kind == activeScm)
                .map(kind -> {
                    Connection c = byKind.get(kind);
                    boolean connected = c != null && c.getState() != IntegrationState.UNINSTALLED;
                    String displayName = manifests
                            .manifestFor(kind)
                            .map(IntegrationManifest::displayName)
                            .orElse(kind.name());
                    return new IntegrationCatalogEntryDTO(
                            kind,
                            displayName,
                            connected,
                            c == null ? null : c.getId(),
                            c == null ? null : c.getState(),
                            c == null ? null : c.credentialsUnreadableSince());
                })
                .toList();
    }

    private static Connection preferred(Connection current, Connection candidate) {
        boolean currentLive = current.getState() != IntegrationState.UNINSTALLED;
        boolean candidateLive = candidate.getState() != IntegrationState.UNINSTALLED;
        if (currentLive != candidateLive) {
            return currentLive ? current : candidate;
        }
        return candidate.getCreatedAt().isAfter(current.getCreatedAt()) ? candidate : current;
    }

    /**
     * Connection health derivation: PENDING/SUSPENDED/UNINSTALLED map directly;
     * ACTIVE folds in the last finished job's outcome, errored resources, and vendor-reported degradation.
     */
    private static ConnectionHealth deriveHealth(
            Connection connection,
            Optional<SyncJob> lastFinishedJob,
            long erroredResources,
            boolean vendorHealthDegraded) {
        return switch (connection.getState()) {
            case PENDING -> ConnectionHealth.PENDING;
            // UNINSTALLED has no further transitions and is not independently modeled in the 5-value
            // health enum — closest semantic is SUSPENDED ("not usable right now").
            case SUSPENDED, UNINSTALLED -> ConnectionHealth.SUSPENDED;
            case ACTIVE -> {
                if (lastFinishedJob
                        .map(SyncJob::getStatus)
                        .filter(s -> s == SyncJobStatus.FAILED)
                        .isPresent()) {
                    yield ConnectionHealth.FAILED;
                }
                boolean warningsJob = lastFinishedJob
                        .map(SyncJob::getStatus)
                        .filter(s -> s == SyncJobStatus.SUCCEEDED_WITH_WARNINGS)
                        .isPresent();
                if (erroredResources > 0 || warningsJob || vendorHealthDegraded) {
                    yield ConnectionHealth.DEGRADED;
                }
                yield ConnectionHealth.HEALTHY;
            }
        };
    }

    private static void rejectDuplicate(Object a, Object b) {
        throw new IllegalStateException("Duplicate registration for the same IntegrationKind: " + a + " vs " + b);
    }
}
