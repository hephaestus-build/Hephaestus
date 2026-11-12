package de.tum.cit.aet.hephaestus.core.runtime;

import de.tum.cit.aet.hephaestus.Application;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The agent runtime's application graph without a cluster: no Kubernetes client. A
 * context built from it resolves every agent runtime bean and starts no sandbox.
 */
@ContextConfiguration(classes = Application.class)
@MockitoBean(name = "sandboxKubernetesClient", types = KubernetesClient.class, enforceOverride = true)
public final class AgentRuntimeWithoutCluster {
    private AgentRuntimeWithoutCluster() {}
}
