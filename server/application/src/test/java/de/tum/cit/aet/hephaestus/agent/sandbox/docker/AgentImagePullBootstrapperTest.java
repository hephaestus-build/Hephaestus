package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentImagePullBootstrapperTest extends BaseUnitTest {

    private static final String IMAGE = "ghcr.io/hephaestus-build/agent-pi:0.73.2";

    @Mock
    private DockerImageOperations imageOps;

    private AgentImagePullBootstrapper bootstrapperWith(ImagePullPolicy policy, SimpleMeterRegistry registry) {
        return new AgentImagePullBootstrapper(
                imageOps,
                new AgentImageProperties(IMAGE, policy),
                registry,
                new AgentImageContractVerifier(imageOps, registry));
    }

    @ParameterizedTest
    @CsvSource({"default,true,true", "specs,true,false", "default,false,false"})
    void shouldOnlyBootstrapImagesForWorkersOutsideSpecGeneration(
            String profile, boolean workerEnabled, boolean expected) {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=" + profile, "hephaestus.runtime.worker.enabled=" + workerEnabled)
                .withUserConfiguration(AgentImagePullBootstrapper.class)
                .withBean(DockerImageOperations.class, () -> imageOps)
                .withBean(AgentImageProperties.class, () -> new AgentImageProperties(IMAGE, ImagePullPolicy.ALWAYS))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AgentImageContractVerifier.class)
                .run(context -> assertThat(context.getBeansOfType(AgentImagePullBootstrapper.class))
                        .hasSize(expected ? 1 : 0));
    }

    @Test
    void shouldSkipPullWhenDaemonUnreachableAndPolicyIsAlways() {
        var registry = new SimpleMeterRegistry();
        when(imageOps.ping()).thenReturn(false);

        bootstrapperWith(ImagePullPolicy.ALWAYS, registry).pullOnStartup();

        verify(imageOps).ping();
        verify(imageOps).imageLabels(IMAGE);
        verifyNoMoreInteractions(imageOps);
        assertThat(registry.get("agent.image.pull.skipped")
                        .tag("reason", "docker_unreachable")
                        .counter()
                        .count())
                .isEqualTo(1d);
    }

    @ParameterizedTest(name = "ALWAYS pull returns {0} → outcome={1}, failure-counter={2}")
    @CsvSource({"true, success, 0", "false, failure, 1"})
    void shouldRecordOutcomeMetricWhenAlwaysPolicyRunsPull(
            boolean pullSucceeds, String outcomeTag, int expectedFailureCount) {
        var registry = new SimpleMeterRegistry();
        when(imageOps.ping()).thenReturn(true);
        when(imageOps.pullImage(IMAGE)).thenReturn(pullSucceeds);

        bootstrapperWith(ImagePullPolicy.ALWAYS, registry).pullOnStartup();

        verify(imageOps, never()).imageIsPresent(IMAGE);
        verify(imageOps).pullImage(IMAGE);
        assertThat(registry.get("agent.image.pull.duration")
                        .tag("outcome", outcomeTag)
                        .timer()
                        .count())
                .isEqualTo(1L);
        if (expectedFailureCount == 0) {
            assertThat(registry.find("agent.image.pull.failure").counter()).isNull();
        } else {
            assertThat(registry.get("agent.image.pull.failure").counter().count())
                    .isEqualTo(expectedFailureCount);
        }
    }

    @ParameterizedTest(name = "IF_NOT_PRESENT, imageIsPresent={0} → pullImage invoked={1}")
    @CsvSource({"true, 0", "false, 1"})
    void shouldHonourCacheWhenPolicyIsIfNotPresent(boolean alreadyPresent, int expectedPulls) {
        when(imageOps.imageIsPresent(IMAGE)).thenReturn(alreadyPresent);
        if (!alreadyPresent) {
            when(imageOps.ping()).thenReturn(true);
            when(imageOps.pullImage(IMAGE)).thenReturn(true);
        }

        bootstrapperWith(ImagePullPolicy.IF_NOT_PRESENT, new SimpleMeterRegistry())
                .pullOnStartup();

        verify(imageOps).imageIsPresent(IMAGE);
        verify(imageOps, alreadyPresent ? never() : Mockito.times(expectedPulls))
                .pullImage(IMAGE);
    }

    @Test
    void shouldReportTheImagesRuntimeContractOnStartup() {
        var registry = new SimpleMeterRegistry();
        when(imageOps.imageIsPresent(IMAGE)).thenReturn(true);
        when(imageOps.imageLabels(IMAGE))
                .thenReturn(Optional.of(Map.of(
                        SandboxLayout.RUNTIME_CONTRACT_LABEL,
                        Integer.toString(SandboxLayout.RUNTIME_CONTRACT_VERSION))));

        bootstrapperWith(ImagePullPolicy.IF_NOT_PRESENT, registry).pullOnStartup();

        assertThat(registry.counter("agent.image.contract", "outcome", "verified")
                        .count())
                .isEqualTo(1d);
    }

    @Test
    void shouldOnlyProbePresenceWhenPolicyIsNever() {
        bootstrapperWith(ImagePullPolicy.NEVER, new SimpleMeterRegistry()).pullOnStartup();

        verify(imageOps).imageIsPresent(any());
        verify(imageOps).imageLabels(IMAGE);
        verifyNoMoreInteractions(imageOps);
    }
}
