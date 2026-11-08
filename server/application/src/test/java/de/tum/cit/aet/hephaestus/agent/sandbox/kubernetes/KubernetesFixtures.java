package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import java.time.Duration;

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
}
