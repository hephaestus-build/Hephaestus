package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerPropertiesConfiguration;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxHosts;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxReconciler;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxRuntime;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxRuntimeConfiguration;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which sandbox runtime a role loads. The worker runs Docker unless Kubernetes is selected; a selected Kubernetes
 * runtime without its configuration, or a runtime the worker does not know, stops the start rather than fall back.
 */
@Tag("unit")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SandboxRuntimeSelectionTest {

    private static final String RUNTIME = SandboxRuntime.PROPERTY + "=";
    private static final String[] KUBERNETES = {
        RUNTIME + "kubernetes",
        "hephaestus.sandbox.kubernetes.namespace=hephaestus-sandboxes",
        "hephaestus.sandbox.kubernetes.pod-name=hephaestus-worker-0",
        "hephaestus.sandbox.kubernetes.pod-ip=10.42.0.7"
    };

    /**
     * The environment of a test JVM must not select a runtime here. The client's own configuration would read the
     * machine's kubeconfig and service account; for each context these system properties, which the client reads
     * before either, turn both off and name a loopback address that nothing calls, and the runner restores them after.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner(() -> {
                var context = new AnnotationConfigApplicationContext();
                context.setEnvironment(new MockEnvironment());
                return context;
            })
            .withSystemProperties(
                    Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY + "=false",
                    Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY + "=false",
                    Config.KUBERNETES_TRYNAMESPACE_PATH_SYSTEM_PROPERTY + "=false",
                    Config.KUBERNETES_MASTER_SYSTEM_PROPERTY + "=https://127.0.0.1:9")
            .withUserConfiguration(
                    SandboxRuntimeConfiguration.class,
                    KubernetesSandboxConfiguration.class,
                    WorkerPropertiesConfiguration.class)
            .withBean(SandboxGatewayProperties.class, () -> new SandboxGatewayProperties(8081, 4_194_304, 120))
            .withBean(SandboxGatewaySessions.class, SandboxGatewaySessions::new)
            .withBean(ReviewJobRepository.class, () -> mock(ReviewJobRepository.class))
            .withBean(WorkerRegistryRepository.class, () -> mock(WorkerRegistryRepository.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(
                    AgentImageProperties.class,
                    () -> new AgentImageProperties("ghcr.io/example/agent:1", ImagePullPolicy.IF_NOT_PRESENT));

    /** Docker's own configuration gives the backend there; it is out of this test, so a stand-in gives it. */
    @Test
    void shouldRunDockerWhenNoRuntimeIsSelected() {
        runner.withBean(SandboxBackend.class, () -> mock(SandboxBackend.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SandboxRuntime.class)).isEqualTo(SandboxRuntime.DOCKER);
            assertThat(context)
                    .doesNotHaveBean(KubernetesClient.class)
                    .doesNotHaveBean(KubernetesSandboxBackend.class)
                    .hasBean("sandboxExecutor");
        });
    }

    @Test
    void shouldWireEveryKubernetesSandboxWhenSelected() {
        runner.withPropertyValues(KUBERNETES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SandboxRuntime.class)).isEqualTo(SandboxRuntime.KUBERNETES);
            assertThat(context.getBean(SandboxBackend.class)).isInstanceOf(KubernetesSandboxBackend.class);
            assertThat(context.getBean(SandboxManager.class)).isInstanceOf(SandboxAttemptRunner.class);
            assertThat(context.getBean(HostSandbox.class)).isInstanceOf(SandboxHosts.class);
            assertThat(context.getBean(ExecSandboxes.class)).isInstanceOf(KubernetesExecSandboxes.class);
            assertThat(context)
                    .hasSingleBean(KubernetesReviewJobs.class)
                    .hasSingleBean(SandboxReconciler.class)
                    .hasSingleBean(KubernetesIsolationProbe.class)
                    .hasBean("sandboxExecutor");
        });
    }

    @Test
    void shouldNotStartWhenKubernetesIsSelectedWithoutItsConfiguration() {
        runner.withPropertyValues(RUNTIME + "kubernetes")
                .run(context ->
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "hephaestus.sandbox.kubernetes.probe-interval=30s",
                "hephaestus.sandbox.kubernetes.node-selector=[\"pool\"]",
                "hephaestus.sandbox.kubernetes.tolerations={}",
                "hephaestus.sandbox.kubernetes.runtime-class-name=Not_A_Name",
                "hephaestus.sandbox.kubernetes.seccomp-profile=../escape.json",
                "hephaestus.sandbox.kubernetes.seccomp-profile=/var/lib/kubelet/seccomp/profile.json"
            })
    void shouldNotStartOnAnInvalidClusterPrerequisite(String setting) {
        runner.withPropertyValues(KUBERNETES)
                .withPropertyValues(setting)
                .run(context ->
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void shouldTakeAnOptionalRuntimeClassAndSeccompProfileWhenNamed() {
        runner.withPropertyValues(KUBERNETES)
                .withPropertyValues(
                        "hephaestus.sandbox.kubernetes.runtime-class-name=gvisor",
                        "hephaestus.sandbox.kubernetes.seccomp-profile=hephaestus/agent-seccomp-profile.json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KubernetesSandboxProperties.class)
                                    .runtimeClassName())
                            .isEqualTo("gvisor");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.42.0.7", "fd00::7", "2001:db8::10", "::ffff:10.42.0.7"})
    void shouldAcceptTheAddressesThatAPodHas(String address) {
        runner.withPropertyValues(KUBERNETES)
                .withPropertyValues("hephaestus.sandbox.kubernetes.pod-ip=" + address)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {":1", "1:::2", "fe80::1%eth0", "10.42.0", "010.42.0.7", "g::1", "1:2:3:4:5:6:7:8:9"})
    void shouldNotStartOnAMalformedWorkerAddress(String address) {
        runner.withPropertyValues(KUBERNETES)
                .withPropertyValues("hephaestus.sandbox.kubernetes.pod-ip=" + address)
                .run(context ->
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void shouldNotStartWhenTheWorkerAddressIsAName() {
        runner.withPropertyValues(KUBERNETES)
                .withPropertyValues("hephaestus.sandbox.kubernetes.pod-ip=worker.hephaestus.svc")
                .run(context ->
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void shouldNotStartOnARuntimeItDoesNotKnow() {
        runner.withPropertyValues(RUNTIME + "podman").run(context -> {
            assertThat(context.getStartupFailure()).isInstanceOf(Exception.class);
            assertThat(context.getStartupFailure()).hasStackTraceContaining(BindException.class.getSimpleName());
        });
    }

    @Test
    void shouldLeaveEverySandboxRuntimeOffOutsideTheWorkerRole() {
        runner.withPropertyValues(RuntimeRole.WORKER_PROPERTY + "=false", RUNTIME + "kubernetes")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(SandboxRuntime.class)
                            .doesNotHaveBean(KubernetesClient.class)
                            .doesNotHaveBean(KubernetesReviewJobs.class)
                            .doesNotHaveBean(SandboxManager.class);
                });
    }
}
