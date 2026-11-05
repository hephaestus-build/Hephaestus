package de.tum.cit.aet.hephaestus.core.runtime;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesIsolationProbe;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxCache;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The worker role's application graph without a cluster: no Kubernetes client, sandbox cache or isolation probe. A
 * context built from it resolves every worker bean and starts no sandbox.
 */
@ContextConfiguration(classes = Application.class)
@MockitoBean(name = "sandboxKubernetesClient", types = KubernetesClient.class, enforceOverride = true)
@MockitoBean(
        types = {KubernetesSandboxCache.class, KubernetesIsolationProbe.class},
        enforceOverride = true)
public final class WorkerGraphWithoutCluster {
    private WorkerGraphWithoutCluster() {}
}
