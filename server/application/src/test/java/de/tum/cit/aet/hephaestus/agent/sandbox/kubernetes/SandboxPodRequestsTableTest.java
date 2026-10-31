package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.mentor.host.HephHostConfiguration;
import de.tum.cit.aet.hephaestus.agent.mentor.host.HephHostSpec;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The chart derives the sandbox quota of each tier from {@code deploy/chart/hephaestus/sandbox-pod-requests.json}. Each entry
 * must be what the worker's Pods request, which the tier quotas count, so a quota is never smaller than the Pods it
 * must hold. A change
 * to a sandbox's size fails here until the table follows it.
 */
class SandboxPodRequestsTableTest extends BaseUnitTest {

    private static final Path TABLE = Path.of("../../deploy/chart/hephaestus/sandbox-pod-requests.json");

    private static final String IMAGE = "ghcr.io/hephaestus-build/agent:test";

    private final KubernetesPods pods = new KubernetesPods(
            mock(KubernetesClient.class), KubernetesFixtures.worker("sandboxes"), 10, ImagePullPolicy.IF_NOT_PRESENT);

    @Test
    @DisplayName("a review sandbox counts what the table says")
    void shouldMatchTheTableWhenTheWorkerBuildsAReviewPod() throws IOException {
        ResourceLimits review = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("hephaestus.sandbox", Bindable.of(SandboxProperties.class))
                .defaultResourceLimits()
                .forAttempt(ResourceLimits.MAX_RUNTIME);

        assertCounts("review", List.of(workspacePod(KubernetesWorker.KIND_REVIEW, review, KubernetesPods.REVIEW)));
    }

    @Test
    @DisplayName("a Hephaestus host counts what the table says")
    void shouldMatchTheTableWhenTheWorkerBuildsAHephHostPod() throws IOException {
        assertCounts(
                "hephHost",
                List.of(workspacePod(KubernetesWorker.KIND_HOST, HephHostSpec.LIMITS, KubernetesPods.HOST)));
    }

    @Test
    @DisplayName("a turn sandbox, with its stager, counts what the table says")
    void shouldMatchTheTableWhenTheWorkerBuildsATurnSandbox() throws IOException {
        Pod turn = pods.turnPod(workload(KubernetesWorker.KIND_TURN), TurnSandboxes.LIMITS);

        assertCounts("turnSandbox", List.of(turn));
    }

    @Test
    @DisplayName("the two Pods of one isolation probe run count what the table says")
    void shouldMatchTheTableWhenTheProbeRunsItsTwoPods() throws IOException {
        Duration time = Duration.ofMinutes(2);
        Function<Long, Pod> probe = memory -> pods.probePod(
                workload(KubernetesWorker.KIND_REVIEW),
                List.of("node"),
                new ResourceLimits(memory, 0.5, 256, time),
                time.toSeconds());

        assertCounts(
                "isolationProbe",
                List.of(
                        probe.apply(KubernetesIsolationProbe.TRIAL_MEMORY),
                        probe.apply(KubernetesIsolationProbe.PROBE_MEMORY)));
    }

    @Test
    @DisplayName("the turn sandbox pool matches the worker")
    void shouldMatchTheWorkerWhenTheTableCountsPooledSandboxes() throws IOException {
        JsonNode table = table();

        assertThat(table.path("pooledTurnSandboxes").asInt()).isEqualTo(HephHostConfiguration.SANDBOX_POOL);
    }

    private void assertCounts(String entry, List<Pod> counted) throws IOException {
        JsonNode expected = table().path(entry);
        long memory = 0;
        long millis = 0;
        long disk = 0;
        for (Pod pod : counted) {
            memory += effective(pod, "memory").longValueExact();
            millis += effective(pod, "cpu").movePointRight(3).longValueExact();
            disk += effective(pod, "ephemeral-storage").longValueExact();
        }

        assertThat(expected.path("pods").asInt()).as(entry + " Pods").isEqualTo(counted.size());
        assertThat(expected.path("memoryMi").asLong() * 1024 * 1024)
                .as(entry + " memory request")
                .isEqualTo(memory);
        assertThat(expected.path("cpuMillis").asLong())
                .as(entry + " CPU request")
                .isEqualTo(millis);
        assertThat(expected.path("ephemeralStorageMi").asLong() * 1024 * 1024)
                .as(entry + " disk request")
                .isEqualTo(disk);
    }

    /** What a quota charges for a Pod's request: its containers together, or its largest init container when more. */
    private static BigDecimal effective(Pod pod, String resource) {
        BigDecimal running = pod.getSpec().getContainers().stream()
                .map(container -> request(container, resource))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal init = pod.getSpec().getInitContainers().stream()
                .map(container -> request(container, resource))
                .reduce(BigDecimal.ZERO, BigDecimal::max);
        return running.max(init);
    }

    private static BigDecimal request(Container container, String resource) {
        Map<String, Quantity> requests = container.getResources().getRequests();
        Quantity quantity = requests == null ? null : requests.get(resource);
        return quantity == null ? BigDecimal.ZERO : quantity.getNumericalAmount();
    }

    /** As {@code KubernetesSandboxBackend.launch} builds it: a review's or a host's size by its kind. */
    private Pod workspacePod(String kind, ResourceLimits limits, KubernetesPods.Size size) {
        return pods.workspacePod(workload(kind), Map.of(), List.of("node"), limits, size);
    }

    private static KubernetesPods.Workload workload(String kind) {
        return new KubernetesPods.Workload(
                "sandbox", KubernetesFixtures.worker("sandboxes").labels(kind), IMAGE, kind);
    }

    private static JsonNode table() throws IOException {
        return new ObjectMapper().readTree(Files.readString(TABLE));
    }
}
