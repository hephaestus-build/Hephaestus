package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesSandboxClaimsTest {

    private static final String NAMESPACE = "hephaestus-sandboxes";
    private static final AgentSandboxProperties.WarmPools POOLS =
            new AgentSandboxProperties.WarmPools("rel-sandbox-review", "rel-sandbox-conversation");

    private @Nullable KubernetesClient client;

    private KubernetesSandboxClaims claims() {
        return new KubernetesSandboxClaims(Objects.requireNonNull(client), NAMESPACE, POOLS);
    }

    private static SandboxClaims.Claim claim(String name) {
        return new SandboxClaims.Claim(
                name,
                UUID.fromString("0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11"),
                AgentSandboxKind.CONVERSATION,
                Instant.parse("2026-11-09T12:00:00.750Z"),
                Map.of("HEPHAESTUS_SANDBOX_TOKEN", "token", "HEPHAESTUS_GATEWAY_URL", "http://10.43.0.12:8081"));
    }

    private GenericKubernetesResource stored(String name) {
        return Objects.requireNonNull(Objects.requireNonNull(client)
                .genericKubernetesResources(KubernetesSandboxClaims.CLAIMS)
                .inNamespace(NAMESPACE)
                .withName(name)
                .get());
    }

    @Test
    void shouldClaimFromTheKindsEmptyWarmPoolWithTheEnvironmentAndTheShutdownOfTheSandbox() {
        claims().create(claim("agent-one"));

        var stored = stored("agent-one");
        assertThat(stored.getMetadata().getLabels())
                .containsEntry(KubernetesSandboxClaims.SANDBOX_LABEL, "0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11")
                .containsEntry("hephaestus.build/sandbox-kind", "conversation");
        assertThat(stored.getAdditionalProperties().get("spec"))
                .isEqualTo(Map.of(
                        "warmPoolRef",
                        Map.of("name", "rel-sandbox-conversation"),
                        "lifecycle",
                        Map.of("shutdownTime", "2026-11-09T12:00:00Z", "shutdownPolicy", "Delete"),
                        "env",
                        List.of(
                                Map.of("name", "HEPHAESTUS_GATEWAY_URL", "value", "http://10.43.0.12:8081"),
                                Map.of("name", "HEPHAESTUS_SANDBOX_TOKEN", "value", "token"))));
    }

    @Test
    void shouldTakeARepeatedCreateAndADeleteOfAClaimThatIsGoneAsDone() {
        claims().create(claim("agent-two"));
        claims().create(claim("agent-two"));
        claims().delete("agent-two");
        claims().delete("agent-two");

        assertThat(claims().names()).doesNotContain("agent-two");
    }

    @Test
    void shouldNameOnlyTheClaimsItCreated() {
        claims().create(claim("agent-three"));
        // The isolation gate claims sandboxes in the same namespace; they are not the server's.
        Objects.requireNonNull(client)
                .genericKubernetesResources(KubernetesSandboxClaims.CLAIMS)
                .inNamespace(NAMESPACE)
                .resource(new GenericKubernetesResourceBuilder()
                        .withApiVersion(KubernetesSandboxClaims.API_VERSION)
                        .withKind(KubernetesSandboxClaims.KIND)
                        .withNewMetadata()
                        .withName("isolation-gate-a")
                        .endMetadata()
                        .withAdditionalProperties(Map.of("spec", Map.of("warmPoolRef", Map.of("name", "probe"))))
                        .build())
                .create();

        assertThat(claims().names()).contains("agent-three").doesNotContain("isolation-gate-a");
    }
}
