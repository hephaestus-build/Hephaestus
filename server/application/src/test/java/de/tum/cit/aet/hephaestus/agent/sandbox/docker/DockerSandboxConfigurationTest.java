package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class DockerSandboxConfigurationTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({"false, false, skipped", "true, false, failure", "true, true, success"})
    void shouldRegisterSandboxPullMetricsLazily(boolean reachable, boolean pulled, String outcome) {
        var operations = mock(DockerClientOperations.class);
        when(operations.ping()).thenReturn(reachable);
        if (reachable) {
            when(operations.pullImage("test-image")).thenReturn(pulled);
        }
        var registry = new SimpleMeterRegistry();
        try {
            var guard = new DockerSandboxConfiguration()
                    .sandboxImageGuard(
                            operations, new AgentImageProperties("test-image", ImagePullPolicy.ALWAYS), registry);
            assertThat(registry.getMeters()).isEmpty();

            guard.ensurePresent("test-image");

            if (!reachable) {
                assertThat(registry.getMeters()).hasSize(1);
                assertThat(registry.get("sandbox.image.pull.skipped")
                                .tag("reason", "docker_unreachable")
                                .counter()
                                .count())
                        .isEqualTo(1);
            } else {
                assertThat(registry.getMeters()).hasSize(pulled ? 1 : 2);
                assertThat(registry.get("sandbox.image.pull.duration")
                                .tag("outcome", outcome)
                                .timer()
                                .count())
                        .isEqualTo(1);
                if (!pulled) {
                    assertThat(registry.get("sandbox.image.pull.failure")
                                    .counter()
                                    .count())
                            .isEqualTo(1);
                }
            }
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"'', false", "http://127.0.0.1:38171, true"})
    void shouldFetchFromTheWorkersOwnNetworkOnlyForAnScmSimulation(String origin, boolean fromWorkerNetwork) {
        assertThat(gitSettings(origin, null).fetchFromWorkerNetwork()).isEqualTo(fromWorkerNetwork);
    }

    @Test
    void shouldRefuseAnScmSimulationUnderAContainerRuntimeThatCannotJoinTheWorkersNetwork() {
        assertThatThrownBy(() -> gitSettings("http://127.0.0.1:38171", "runsc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_DOCKER_CONTAINER_RUNTIME");
        assertThat(gitSettings("", "runsc").fetchFromWorkerNetwork()).isFalse();
    }

    private static DockerNativeGitExecutor.Settings gitSettings(String simulationOrigin, @Nullable String runtime) {
        var environment = new MockEnvironment().withProperty("hephaestus.e2e.scm-origin", simulationOrigin);
        environment.setActiveProfiles("e2e");
        return new DockerSandboxConfiguration()
                .gitPreparationSettings(
                        new GitRepositoryProperties(true, 2, "git-preparation:test", 1L << 33),
                        new WorkerProperties(
                                "worker",
                                new WorkerProperties.Capacity("1", "1"),
                                new WorkerProperties.Drain(Duration.ofMinutes(1)),
                                new WorkerProperties.Heartbeat(Duration.ofSeconds(20)),
                                new WorkerProperties.Control(
                                        URI.create("ws://example"), "token", Duration.ofSeconds(10))),
                        new SandboxProperties(5, 10, 60, null),
                        new DockerSandboxProperties(
                                "unix:///var/run/docker.sock", false, null, runtime, null, "default"),
                        new ScmServerEndpointPolicy(environment));
    }

    @Test
    void shouldOutliveTheLongestSandboxWhenWaitingForAContainerToExit() {
        // `docker wait` is silent until the container exits, so this timeout is a ceiling on the
        // container's life. At or below MAX_RUNTIME it would cut a legitimate wait short.
        assertThat(DockerSandboxConfiguration.HTTP_STREAMING_RESPONSE_TIMEOUT)
                .isGreaterThan(ResourceLimits.MAX_RUNTIME);
    }

    @Test
    void shouldGiveOrdinaryRequestsMoreThanTheLongestBudgetedCallCanTake() {
        // pullImage budgets itself 5 minutes; a shorter idle timeout would pre-empt that budget.
        assertThat(DockerSandboxConfiguration.HTTP_RESPONSE_TIMEOUT)
                .isGreaterThan(DockerClientOperations.IMAGE_PULL_TIMEOUT);
    }
}
