package de.tum.cit.aet.hephaestus.core.release.kubernetes;

import de.tum.cit.aet.hephaestus.core.release.ReleaseProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The rollout of this server through the Kubernetes API, with the server's own service account token. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnServerRole
@ConditionalOnExpression("'${hephaestus.release.pod:}' != ''")
class KubernetesRolloutConfiguration {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    @Bean(destroyMethod = "close")
    KubernetesRolloutReader rolloutReader(ReleaseProperties properties) {
        Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout((int) REQUEST_TIMEOUT.toMillis())
                .withConnectionTimeout((int) CONNECTION_TIMEOUT.toMillis())
                .build();
        // In a Pod, the client reads its namespace from the service account mount.
        if (config.getNamespace() == null || config.getNamespace().isBlank()) {
            throw new IllegalStateException("hephaestus.release.pod is set, but the Pod's namespace is unknown");
        }
        return new KubernetesRolloutReader(
                new KubernetesClientBuilder().withConfig(config).build(), config.getNamespace(), properties.pod());
    }
}
