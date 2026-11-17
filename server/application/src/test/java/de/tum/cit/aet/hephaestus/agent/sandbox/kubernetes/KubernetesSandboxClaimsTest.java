package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxClaims;
import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@EnableKubernetesMockClient(crud = true, kubernetesClientBuilderCustomizer = NoRequestRetries.class)
class KubernetesSandboxClaimsTest {

    private static final String NAMESPACE = "hephaestus-sandboxes";
    private static final AgentSandboxProperties.WarmPools POOLS =
            new AgentSandboxProperties.WarmPools("rel-sandbox-review", "rel-sandbox-conversation");

    private @Nullable KubernetesClient client;
    private @Nullable KubernetesMockServer server;

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
                        Map.of("shutdownTime", "2026-11-09T12:00:00Z", "shutdownPolicy", "DeleteForeground"),
                        "env",
                        List.of(
                                Map.of("name", "HEPHAESTUS_GATEWAY_URL", "value", "http://10.43.0.12:8081"),
                                Map.of("name", "HEPHAESTUS_SANDBOX_TOKEN", "value", "token"))));
    }

    @Test
    void shouldTakeARepeatedCreateAndADeleteOfAClaimThatIsGoneAsDone() throws InterruptedException {
        claims().create(claim("agent-two"));
        claims().create(claim("agent-two"));
        KubernetesMockServer api = Objects.requireNonNull(server);
        while (api.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // Drop the requests of the setup.
        }
        claims().delete("agent-two");
        RecordedRequest deletion = Objects.requireNonNull(api.takeRequest(5, TimeUnit.SECONDS));
        assertThat(deletion.getMethod()).isEqualTo("DELETE");
        DeleteOptions options = Objects.requireNonNull(client)
                .getKubernetesSerialization()
                .unmarshal(deletion.getUtf8Body(), DeleteOptions.class);
        assertThat(options.getPropagationPolicy()).isEqualTo("Foreground");
        assertThat(Objects.requireNonNull(client)
                        .genericKubernetesResources(KubernetesSandboxClaims.CLAIMS)
                        .inNamespace(NAMESPACE)
                        .withName("agent-two")
                        .get())
                .isNull();
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

    @Test
    void shouldReadOnlyTheEndOfTheSandboxContainerFromThePodsStatus() {
        var created = Objects.requireNonNull(client)
                .pods()
                .inNamespace(NAMESPACE)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("agent-four")
                        .addToAnnotations("note", "not a status field")
                        .endMetadata()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("agent")
                        .withImage("agent")
                        .addNewEnv()
                        .withName("HEPHAESTUS_SANDBOX_TOKEN")
                        .withValue("token")
                        .endEnv()
                        .endContainer()
                        .endSpec()
                        .build())
                .create();
        // The kubelet restarted the container after the kernel killed it; it runs again.
        withStatus(
                created,
                new PodStatusBuilder()
                        .withPhase("Running")
                        .withMessage("free text of the kubelet")
                        .addNewContainerStatus()
                        .withName("agent")
                        .withRestartCount(2)
                        .withNewState()
                        .withNewRunning()
                        .endRunning()
                        .endState()
                        .withNewLastState()
                        .withNewTerminated()
                        .withReason("OOMKilled")
                        .withExitCode(137)
                        .withMessage("free text of the runtime")
                        .endTerminated()
                        .endLastState()
                        .endContainerStatus()
                        .build());

        assertThat(claims().podEnd("agent-four"))
                .contains(new SandboxClaims.PodEnd("Running", null, "OOMKilled", 137, 2, false));
    }

    @Test
    void shouldPreferTheCurrentTerminationAndReadThePodsOwnReason() {
        var created = Objects.requireNonNull(client)
                .pods()
                .inNamespace(NAMESPACE)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("agent-five")
                        .endMetadata()
                        .build())
                .create();
        withStatus(
                created,
                new PodStatusBuilder()
                        .withPhase("Failed")
                        .withReason("Evicted")
                        .addNewContainerStatus()
                        .withName("agent")
                        .withRestartCount(0)
                        .withNewState()
                        .withNewTerminated()
                        .withReason("Error")
                        .withExitCode(137)
                        .endTerminated()
                        .endState()
                        .withNewLastState()
                        .withNewTerminated()
                        .withReason("OOMKilled")
                        .withExitCode(137)
                        .endTerminated()
                        .endLastState()
                        .endContainerStatus()
                        .build());

        assertThat(claims().podEnd("agent-five"))
                .contains(new SandboxClaims.PodEnd("Failed", "Evicted", "Error", 137, 0, false));
    }

    @Test
    void shouldAnswerNothingForAPodThatIsGone() {
        assertThat(claims().podEnd("agent-gone")).isEmpty();
    }

    @Test
    void shouldTakeOnlyAnEarlierTerminationOfAPodThatIsBeingDeleted() {
        var stopping = new PodBuilder()
                .withNewMetadata()
                .withName("agent-six")
                .withDeletionTimestamp("2026-11-15T10:00:00Z")
                .endMetadata()
                .withStatus(new PodStatusBuilder()
                        .withPhase("Running")
                        .addNewContainerStatus()
                        .withName("agent")
                        .withRestartCount(0)
                        .withNewState()
                        .withNewTerminated()
                        .withReason("Error")
                        .withExitCode(143)
                        .endTerminated()
                        .endState()
                        .endContainerStatus()
                        .build())
                .build();

        assertThat(KubernetesSandboxClaims.end(stopping))
                .as("the stop signal's exit is the deletion's, not the sandbox's")
                .isEqualTo(new SandboxClaims.PodEnd("Running", null, null, null, 0, true));
    }

    @Test
    void shouldPassOnARefusedReadWithItsStatusAndNotTheApisMessage() {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath("/api/v1/namespaces/" + NAMESPACE + "/pods/agent-forbidden")
                .andReturn(
                        403,
                        Map.of(
                                "kind",
                                "Status",
                                "apiVersion",
                                "v1",
                                "status",
                                "Failure",
                                "reason",
                                "Forbidden",
                                "code",
                                403,
                                "message",
                                "pods is forbidden: free text of the API"))
                .once();

        assertThatThrownBy(() -> claims().podEnd("agent-forbidden"))
                .isInstanceOfSatisfying(SandboxClaims.PodReadException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.errorType()).startsWith("io.fabric8.kubernetes.client.");
                    assertThat(failure.getMessage()).doesNotContain("free text");
                    assertThat(failure.getCause()).isNull();
                });
    }

    private void withStatus(Pod created, PodStatus status) {
        created.setStatus(status);
        Objects.requireNonNull(client)
                .pods()
                .inNamespace(NAMESPACE)
                .resource(created)
                .updateStatus();
    }
}
