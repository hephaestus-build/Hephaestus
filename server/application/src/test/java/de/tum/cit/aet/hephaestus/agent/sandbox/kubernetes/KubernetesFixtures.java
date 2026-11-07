package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import java.time.Duration;
import java.util.List;

/** The worker that the Kubernetes runtime tests run as, with no node selector, toleration or RuntimeClass. */
final class KubernetesFixtures {

    static final String WORKER_ID = "worker-process-1";

    private KubernetesFixtures() {}

    static KubernetesSandboxProperties properties(String namespace) {
        return new KubernetesSandboxProperties(
                namespace, "hephaestus-worker-0", "10.42.0.7", "", "", "", "", "", "", Duration.ofMinutes(15), "");
    }

    static KubernetesWorker worker(String namespace) {
        return new KubernetesWorker(properties(namespace), WORKER_ID, 8081);
    }

    /** The registry as it reads while the given worker processes beat. */
    static KubernetesLiveWorkers live(String... workerIds) {
        WorkerRegistryRepository registry = mock(WorkerRegistryRepository.class);
        when(registry.findLiveWorkerIds(anyLong())).thenReturn(List.of(workerIds));
        return new KubernetesLiveWorkers(registry);
    }
}
