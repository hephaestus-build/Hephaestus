package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * Detects and cleans up orphaned sandbox resources.
 *
 * <p>Two triggers clean resources owned by this worker's Docker daemon:
 *
 * <ol>
 *   <li><b>Startup</b> ({@link ApplicationReadyEvent}): resources left by a previous worker process
 *       are cleaned immediately.
 *   <li><b>Periodic</b> (worker maintenance scheduler): orphaned containers and networks are cleaned up on a
 *       configurable interval.
 * </ol>
 *
 * <p>Each operation is idempotent and wrapped in try-catch. A partial failure in one resource does
 * not block cleanup of others.
 */
@WorkspaceAgnostic("Sandbox reconciler operates on Docker infrastructure, not workspace-scoped data")
public class SandboxReconciler {

    private static final Logger log = LoggerFactory.getLogger(SandboxReconciler.class);
    private static final String MDC_RECONCILER_TYPE = "reconciler.type";

    /** A sandbox younger than this is never reaped, so a starting job is not mistaken for an abandoned one. */
    private static final Duration REAP_GRACE = Duration.ofMinutes(2);

    private final AgentJobRepository jobRepository;
    private final SandboxContainerManager containerManager;
    private final SandboxNetworkManager networkManager;
    private final Counter orphanedContainers;
    private final Counter orphanedNetworks;
    private final Counter completedSweeps;
    private final Counter skippedSweeps;
    private final Timer reconciliationDuration;
    private final Clock clock;

    public SandboxReconciler(
            AgentJobRepository jobRepository,
            SandboxContainerManager containerManager,
            SandboxNetworkManager networkManager,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.containerManager = containerManager;
        this.networkManager = networkManager;
        this.clock = clock;
        this.orphanedContainers = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_ORPHANED)
                .tag("resource", "container")
                .description("Orphaned containers removed")
                .register(meterRegistry);
        this.orphanedNetworks = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_ORPHANED)
                .tag("resource", "network")
                .description("Orphaned networks removed")
                .register(meterRegistry);
        this.completedSweeps = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                .tag("outcome", "completed")
                .description("Reconciliation sweeps that ran to completion")
                .register(meterRegistry);
        this.skippedSweeps = Counter.builder(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                .tag("outcome", "skipped")
                .description("Reconciliation sweeps skipped because an inventory they depend on was unreadable")
                .register(meterRegistry);
        this.reconciliationDuration = Timer.builder(AgentMetrics.SANDBOX_RECONCILER_DURATION)
                .description("Duration of periodic reconciliation sweeps")
                .register(meterRegistry);
    }

    /** On startup, clean only resources on this worker's Docker daemon. */
    public void onStartup() {
        MDC.put(MDC_RECONCILER_TYPE, "startup");
        try {
            doStartup();
        } finally {
            MDC.remove(MDC_RECONCILER_TYPE);
        }
    }

    private void doStartup() {
        log.info("Sandbox reconciler: startup check");
        cleanupOrphanedDockerResources();
    }

    /** Clean up Docker resources left from previous runs — don't wait for periodic sweep. */
    private void cleanupOrphanedDockerResources() {
        activeJobIds().ifPresent(this::sweep);
    }

    /** Containers are swept first: what survives there decides which networks are still in use. */
    private void sweep(Set<UUID> activeJobIds) {
        cleanupOrphanedContainers(activeJobIds).ifPresent(inUse -> {
            cleanupOrphanedNetworks(activeJobIds, inUse);
            completedSweeps.increment();
        });
    }

    /**
     * The jobs that may legitimately own a sandbox, or empty when that set cannot be read. Empty is
     * not the empty set: a sweep that cannot see the jobs would treat every live sandbox as an orphan.
     */
    private Optional<Set<UUID>> activeJobIds() {
        try {
            return Optional.of(
                    jobRepository.findByStatusIn(List.of(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING)).stream()
                            .map(AgentJob::getId)
                            .collect(Collectors.toSet()));
        } catch (Exception e) {
            log.warn("Skipping sandbox reconciliation — active jobs are unreadable: {}", e.getMessage());
            skippedSweeps.increment();
            return Optional.empty();
        }
    }

    /** Periodic sweep: clean up orphaned Docker resources. */
    public void periodicReconciliation() {
        MDC.put(MDC_RECONCILER_TYPE, "periodic");
        try {
            reconciliationDuration.record(() -> {
                log.trace("Sandbox reconciler: periodic sweep");

                activeJobIds().ifPresent(this::sweep);
            });
        } finally {
            MDC.remove(MDC_RECONCILER_TYPE);
        }
    }

    /**
     * Removes abandoned containers and reports the ids whose networks are still in use, or empty when
     * the container inventory is unreadable — the network sweep would then have nothing to spare from.
     */
    private Optional<Set<UUID>> cleanupOrphanedContainers(Set<UUID> activeJobIds) {
        Set<UUID> inUse = new HashSet<>();
        try {
            List<DockerOperations.ContainerInfo> containers = containerManager.listManagedContainers();
            for (DockerOperations.ContainerInfo container : containers) {
                if (SandboxLabels.KIND_INTERACTIVE.equals(container.labels().get(SandboxLabels.KIND))) {
                    // A mentor session names its network by session id, which is never an agent-job id.
                    parseUuid(container.labels().get(SandboxLabels.SESSION_ID)).ifPresent(inUse::add);
                    continue;
                }
                String jobIdStr = container.labels().get(SandboxLabels.JOB_ID);
                if (jobIdStr == null) {
                    continue;
                }
                try {
                    UUID jobId = UUID.fromString(jobIdStr);
                    if (activeJobIds.contains(jobId) || isYoung(container)) {
                        inUse.add(jobId);
                        continue;
                    }
                    log.warn("Removing orphaned container: id={}, jobId={}", container.id(), jobId);
                    containerManager.forceRemove(container.id());
                    orphanedContainers.increment();
                } catch (IllegalArgumentException e) {
                    log.warn("Container {} has invalid job-id label: {}", container.id(), jobIdStr);
                } catch (Exception e) {
                    // Still there, so its network is still in use.
                    inUse.add(UUID.fromString(jobIdStr));
                    log.warn("Failed to cleanup orphaned container {}: {}", container.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Skipping sandbox reconciliation — managed containers are unreadable: {}", e.getMessage());
            skippedSweeps.increment();
            return Optional.empty();
        }
        return Optional.of(inUse);
    }

    /** The job set is read before the container list, so a sandbox started in between is not in it. */
    private boolean isYoung(DockerOperations.ContainerInfo container) {
        return container.createdAt() == null
                || container.createdAt().isAfter(clock.instant().minus(REAP_GRACE));
    }

    private static Optional<UUID> parseUuid(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void cleanupOrphanedNetworks(Set<UUID> activeJobIds, Set<UUID> inUse) {
        try {
            List<DockerOperations.NetworkInfo> networks = networkManager.listOrphanedNetworks();

            for (DockerOperations.NetworkInfo network : networks) {
                // The suffix is a job id, or a mentor session id for an interactive sandbox.
                String name = network.name();
                if (!name.startsWith(networkManager.networkPrefix())) {
                    continue;
                }
                String jobIdStr = name.substring(networkManager.networkPrefix().length());
                try {
                    UUID jobId = UUID.fromString(jobIdStr);
                    if (!activeJobIds.contains(jobId) && !inUse.contains(jobId)) {
                        log.warn("Removing orphaned network: id={}, name={}", network.id(), name);
                        networkManager.forceRemoveNetwork(network.id(), name);
                        orphanedNetworks.increment();
                    }
                } catch (IllegalArgumentException e) {
                    log.debug("Network {} has non-UUID suffix: {}", name, jobIdStr);
                } catch (Exception e) {
                    log.warn("Failed to cleanup orphaned network {}: {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan for orphaned networks: {}", e.getMessage());
        }
    }
}
