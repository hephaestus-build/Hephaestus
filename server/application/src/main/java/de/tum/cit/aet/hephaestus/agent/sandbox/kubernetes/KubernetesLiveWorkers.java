package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workers that still beat in {@code worker_registry}, within the lease after which the ledger requeues a worker's
 * jobs ({@link AgentProperties#WORKER_LEASE_TTL}): the one fact by which a sandbox's worker counts as gone. It is the
 * same fact for every worker, so none needs to see another worker's Pod.
 */
@WorkspaceAgnostic("Fleet-wide worker liveness; not workspace-scoped.")
public class KubernetesLiveWorkers {

    private static final Logger log = LoggerFactory.getLogger(KubernetesLiveWorkers.class);

    private final WorkerRegistryRepository registry;

    public KubernetesLiveWorkers(WorkerRegistryRepository registry) {
        this.registry = registry;
    }

    /**
     * The live workers as sandbox labels record them ({@link KubernetesWorker#labelValue}), or empty when the registry
     * cannot be read: an unknown state is not gone.
     */
    public Optional<Set<String>> labels() {
        try {
            return Optional.of(registry.findLiveWorkerIds(AgentProperties.WORKER_LEASE_TTL.toSeconds()).stream()
                    .map(KubernetesWorker::labelValue)
                    .collect(Collectors.toUnmodifiableSet()));
        } catch (RuntimeException e) {
            log.warn("Could not read the live workers: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
