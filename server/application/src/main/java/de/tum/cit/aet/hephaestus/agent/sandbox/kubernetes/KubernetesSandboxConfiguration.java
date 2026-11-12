package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The sandbox owner's bounded Kubernetes client and its SandboxClaim adapter. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnAgentRuntime
@EnableConfigurationProperties({KubernetesSandboxProperties.class, AgentSandboxProperties.class})
public class KubernetesSandboxConfiguration {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    @Bean(destroyMethod = "close")
    public KubernetesClient sandboxKubernetesClient() {
        Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout((int) REQUEST_TIMEOUT.toMillis())
                .withConnectionTimeout((int) CONNECTION_TIMEOUT.toMillis())
                .build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    @Bean
    public SandboxClaims sandboxClaims(
            KubernetesClient sandboxKubernetesClient,
            KubernetesSandboxProperties properties,
            AgentSandboxProperties sandboxes) {
        return new KubernetesSandboxClaims(sandboxKubernetesClient, properties.namespace(), sandboxes.warmPools());
    }
}
