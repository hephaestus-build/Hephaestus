package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The isolation probe's verdicts. Its Pods run only on a live cluster, so here the probe reads the lines a probe Pod
 * prints and the answers an API server gives to the dry run; the live qualification runs the Pods.
 */
@Tag("unit")
@EnableKubernetesMockClient
class KubernetesIsolationProbeTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";
    private static final String PODS = "/api/v1/namespaces/" + SANDBOXES + "/pods?dryRun=All";

    private static final List<KubernetesIsolationProbe.Target> TARGETS = List.of(
            new KubernetesIsolationProbe.Target("gateway", "10.42.0.7", 8081, true),
            new KubernetesIsolationProbe.Target("Kubernetes API", "10.43.0.1", 443, false));

    private @Nullable KubernetesMockServer server;
    private @Nullable KubernetesClient client;

    private static final String CONFINED = "\"token\":false,\"rootWritable\":false,\"uid\":1000";

    private static JsonNode line(String json) {
        JsonNode parsed = KubernetesIsolationProbe.parse(json);
        assertThat(parsed).as("fixture condition: the line is a JSON object").isNotNull();
        return Objects.requireNonNull(parsed);
    }

    @Test
    void shouldPassWhenOnlyTheGatewayAnswersInAConfinedPod() {
        JsonNode result = line("{\"reached\":{\"gateway\":true,\"Kubernetes API\":false}," + CONFINED + "}");

        assertThat(KubernetesIsolationProbe.judge("the review probe Pod", TARGETS, result))
                .isEmpty();
    }

    @Test
    void shouldFailWhenADeniedTargetAnswersOrTheGatewayDoesNot() {
        JsonNode result = line("{\"reached\":{\"gateway\":false,\"Kubernetes API\":true}," + CONFINED + "}");

        assertThat(KubernetesIsolationProbe.judge("the review probe Pod", TARGETS, result))
                .containsExactly(
                        "the review probe Pod does not reach the gateway (10.42.0.7:8081)",
                        "the review probe Pod reaches the Kubernetes API (10.43.0.1:443)");
    }

    @Test
    void shouldFailAPodWithATokenAWritableRootOrTheRootUser() {
        JsonNode result = line("{\"reached\":{\"gateway\":true,\"Kubernetes API\":false},"
                + "\"token\":true,\"rootWritable\":true,\"uid\":0}");

        assertThat(KubernetesIsolationProbe.judge("the review probe Pod", TARGETS, result))
                .containsExactly(
                        "the review probe Pod has a service account token",
                        "the review probe Pod can write its root file system",
                        "the review probe Pod runs as root");
    }

    @Test
    void shouldFailWhenThePodPrintsNoResultOrSkipsATarget() {
        assertThat(KubernetesIsolationProbe.parse("node: not found")).isNull();
        assertThat(KubernetesIsolationProbe.judge(
                        "the review probe Pod", TARGETS, line("{\"reached\":{\"gateway\":true}," + CONFINED + "}")))
                .containsExactly("the review probe Pod did not try the Kubernetes API");
    }

    @Test
    void shouldRecordTheProcessLimitOnlyWhenTheNodeRefusedAProcess() {
        assertThat(KubernetesIsolationProbe.processLimit(line("{\"spawned\":243,\"spawnError\":\"EAGAIN\"}")))
                .isEqualTo(243);
        assertThat(KubernetesIsolationProbe.processLimit(line("{\"spawned\":1024,\"spawnError\":null}")))
                .as("every process it tried started: no limit")
                .isNull();
        assertThat(KubernetesIsolationProbe.processLimit(line("{\"spawned\":3,\"spawnError\":\"ENOENT\"}")))
                .as("a process that could not start for another reason proves no limit")
                .isNull();
    }

    @Test
    void shouldCountAUserNamespaceOnlyWhenThePodsUsersAreNotTheNodes() {
        assertThat(KubernetesIsolationProbe.ownUserNamespace(line("{\"uidMap\":\"0 1879048192 65536\"}")))
                .isTrue();
        assertThat(KubernetesIsolationProbe.ownUserNamespace(line("{\"uidMap\":\"0 0 4294967295\"}")))
                .isFalse();
        assertThat(KubernetesIsolationProbe.ownUserNamespace(line("{\"uidMap\":null}")))
                .isFalse();
        assertThat(KubernetesIsolationProbe.ownUserNamespace(null)).isFalse();
    }

    @Test
    void shouldDescribeTheIsolationTierForTheOperator() {
        var strong = new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "gvisor", 243);
        var plain = new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of("x"), false, "", null);

        assertThat(strong.passed()).isTrue();
        assertThat(strong.tier()).isEqualTo("runtime class gvisor, user namespaces, about 243 processes per Pod");
        assertThat(plain.passed()).isFalse();
        assertThat(plain.tier()).isEqualTo("the cluster's default runtime, the node's users, no process limit per Pod");
    }

    @Test
    void shouldAcceptOnlyARestrictedPodSecurityRefusalOfTheDryRun() {
        KubernetesMockServer api = Objects.requireNonNull(server);
        api.expect()
                .post()
                .withPath(PODS)
                .andReturn(
                        403,
                        new StatusBuilder()
                                .withCode(403)
                                .withReason("Forbidden")
                                .withMessage("pods \"probe\" is forbidden: violates PodSecurity \"restricted:latest\":"
                                        + " allowPrivilegeEscalation != false")
                                .build())
                .once();
        api.expect()
                .post()
                .withPath(PODS)
                .andReturn(
                        403,
                        new StatusBuilder()
                                .withCode(403)
                                .withReason("Forbidden")
                                .withMessage("pods is forbidden: User cannot create resource")
                                .build())
                .once();
        api.expect().post().withPath(PODS).andReturn(201, "{}").once();
        KubernetesIsolationProbe probe = probe();

        assertThat(probe.refusesUnrestrictedPods()).isEmpty();
        assertThat(probe.refusesUnrestrictedPods())
                .as("an RBAC denial is no Pod Security refusal")
                .isPresent();
        assertThat(probe.refusesUnrestrictedPods())
                .hasValueSatisfying(failure -> assertThat(failure).contains("admits a Pod"));
    }

    @Test
    void shouldKeepThePassingProofWhenALaterProbeCannotStartItsPodsAndFailWhenAGateFails() {
        KubernetesMockServer api = Objects.requireNonNull(server);
        var unavailable = new StatusBuilder()
                .withCode(503)
                .withReason("ServiceUnavailable")
                .withMessage("the server is currently unable to handle the request")
                .build();
        var restricted = new StatusBuilder()
                .withCode(403)
                .withReason("Forbidden")
                .withMessage("pods \"probe\" is forbidden: violates PodSecurity \"restricted:latest\"")
                .build();
        api.expect().post().withPath(PODS).andReturn(403, restricted).once();
        api.expect().post().withPath(PODS).andReturn(201, "{}").once();
        api.expect()
                .post()
                .withPath("/apis/batch/v1/namespaces/" + SANDBOXES + "/jobs")
                .andReturn(503, unavailable)
                .always();
        var worker = KubernetesFixtures.worker(SANDBOXES);
        KubernetesClient client = Objects.requireNonNull(this.client);
        var probe = new KubernetesIsolationProbe(
                client,
                new KubernetesPods(client, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(client, worker),
                worker,
                "agent",
                9090);
        var passed = new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "", 248);
        worker.proved(passed);

        assertThat(probe.run())
                .as("Pods whose Jobs the API did not create prove nothing")
                .isSameAs(passed);
        assertThat(worker.isolated()).isTrue();

        KubernetesIsolationProbe.Proof failed = probe.run();
        assertThat(failed.passed())
                .as("the dry run's admission is a gate that ran")
                .isFalse();
        assertThat(failed.failures()).anySatisfy(failure -> assertThat(failure).contains("admits a Pod"));
        assertThat(worker.isolated()).isFalse();
    }

    @Test
    void shouldLeaveTheWorkerWithoutAProofAtItsStartWhileTheProbeCannotStartItsPods() {
        KubernetesMockServer api = Objects.requireNonNull(server);
        var unavailable = new StatusBuilder()
                .withCode(503)
                .withReason("ServiceUnavailable")
                .withMessage("the server is currently unable to handle the request")
                .build();
        var restricted = new StatusBuilder()
                .withCode(403)
                .withReason("Forbidden")
                .withMessage("pods \"probe\" is forbidden: violates PodSecurity \"restricted:latest\"")
                .build();
        api.expect().post().withPath(PODS).andReturn(403, restricted).always();
        api.expect()
                .post()
                .withPath("/apis/batch/v1/namespaces/" + SANDBOXES + "/jobs")
                .andReturn(503, unavailable)
                .always();
        var worker = KubernetesFixtures.worker(SANDBOXES);
        KubernetesClient client = Objects.requireNonNull(this.client);
        var probe = new KubernetesIsolationProbe(
                client,
                new KubernetesPods(client, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(client, worker),
                worker,
                "agent",
                9090);
        assertThat(probe.probe(true))
                .as("at the worker's start, Pods that could not run prove nothing either way, so prove() tries again")
                .isNull();
        assertThat(worker.proof())
                .as("the worker has no proof, so it stays not ready and starts no sandbox")
                .isNull();
        assertThat(worker.isolated()).isFalse();
    }

    @Test
    void shouldStopTheWorkerAtItsStartWhenAGateThatRanFailed() {
        KubernetesMockServer api = Objects.requireNonNull(server);
        // The dry run admits a Pod that Restricted Pod Security refuses: a gate that ran and failed.
        api.expect().post().withPath(PODS).andReturn(201, "{}").always();
        api.expect()
                .post()
                .withPath("/apis/batch/v1/namespaces/" + SANDBOXES + "/jobs")
                .andReturn(503, new StatusBuilder().withCode(503).build())
                .always();

        assertThatThrownBy(() -> probe().prove())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admits a Pod");
    }

    private KubernetesIsolationProbe probe() {
        KubernetesClient api = Objects.requireNonNull(client);
        var worker = KubernetesFixtures.worker(SANDBOXES);
        return new KubernetesIsolationProbe(
                api,
                new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, worker),
                worker,
                "agent",
                8080);
    }
}
