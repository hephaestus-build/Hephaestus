package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** A worker that runs Kubernetes sandboxes loads no bean that needs a Docker daemon. */
@Tag("unit")
class DockerRuntimeSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner(() -> {
                var context = new AnnotationConfigApplicationContext();
                context.setEnvironment(new MockEnvironment());
                return context;
            })
            .withUserConfiguration(AgentImageContractVerifier.class)
            .withBean(DockerImageOperations.class, () -> mock(DockerImageOperations.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void shouldLoadDockerBeansWhenDockerIsTheRuntime() {
        runner.run(context -> assertThat(context).hasSingleBean(AgentImageContractVerifier.class));
        runner.withPropertyValues(SandboxRuntime.PROPERTY + "=docker")
                .run(context -> assertThat(context).hasSingleBean(AgentImageContractVerifier.class));
    }

    @Test
    void shouldLeaveDockerBeansOffWhenKubernetesIsTheRuntime() {
        runner.withPropertyValues(SandboxRuntime.PROPERTY + "=kubernetes")
                .run(context -> assertThat(context).doesNotHaveBean(AgentImageContractVerifier.class));
    }
}
