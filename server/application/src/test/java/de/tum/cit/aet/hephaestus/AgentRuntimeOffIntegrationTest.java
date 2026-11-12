package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxProperties;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "AGENT_ENABLED=false",
            "hephaestus.runtime.server.enabled=true",
            "hephaestus.runtime.webhook.enabled=false",
            "hephaestus.sandbox.kubernetes.namespace=",
            "hephaestus.sandbox.kubernetes.pod-name=",
            "hephaestus.sandbox.kubernetes.pod-ip=",
            "hephaestus.sync.nats.enabled=false"
        })
@ActiveProfiles({"test", "e2e"})
@Import({TestSecurityConfig.class, TestAsyncConfiguration.class})
@Tag("integration")
class AgentRuntimeOffIntegrationTest {
    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createMigratedDatabase("agent_runtime_off");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldStartTheServerWithoutPodMetadataWhenTheAgentRuntimeIsOff() {
        assertThat(context.getEnvironment().getProperty("hephaestus.agent.enabled", Boolean.class))
                .isFalse();
        assertThat(context.getBeansOfType(KubernetesSandboxProperties.class)).isEmpty();
        assertThat(context.getBeansOfType(KubernetesClient.class)).isEmpty();
    }
}
