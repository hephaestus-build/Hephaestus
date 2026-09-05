package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.dockerjava.api.DockerClient;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class DockerSandboxPropertiesTest {

    private final ApplicationContextRunner runner =
            isolatedRunner().withUserConfiguration(PropertiesConfiguration.class);

    private static ApplicationContextRunner isolatedRunner() {
        return new ApplicationContextRunner(() -> {
            var context = new AnnotationConfigApplicationContext();
            context.setEnvironment(new MockEnvironment());
            return context;
        });
    }

    @Test
    void shouldKeepDockerAndGatewayFieldsOutOfTheNeutralNamespace() {
        Set<String> neutralFields = recordComponentNames(SandboxProperties.class);
        assertThat(neutralFields).doesNotContainAnyElementsOf(recordComponentNames(DockerSandboxProperties.class));
        assertThat(neutralFields).doesNotContainAnyElementsOf(recordComponentNames(SandboxGatewayProperties.class));
    }

    private static Set<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void shouldUseLocalDockerDefaultsWithoutChangingGatewayOrCapacity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DockerSandboxProperties.class))
                    .isEqualTo(new DockerSandboxProperties(
                            "unix:///var/run/docker.sock", false, null, null, null, "docker"));
            assertThat(context.getBean(SandboxGatewayProperties.class).port()).isEqualTo(8081);
            assertThat(context.getBean(SandboxProperties.class).maxConcurrentContainers())
                    .isEqualTo(5);
        });
    }

    @Test
    void shouldBindDockerGatewayAndNeutralSettingsIndependently() {
        runner.withPropertyValues(
                        "hephaestus.sandbox.docker.host=tcp://docker:2376",
                        "hephaestus.sandbox.docker.tls-verify=true",
                        "hephaestus.sandbox.docker.cert-path=/run/docker-certs",
                        "hephaestus.sandbox.docker.container-runtime=runsc",
                        "hephaestus.sandbox.docker.app-server-container-id=worker-id",
                        "hephaestus.sandbox.docker.cli=/usr/bin/docker",
                        "hephaestus.sandbox.gateway.port=9081",
                        "hephaestus.sandbox.max-concurrent-containers=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DockerSandboxProperties.class))
                            .isEqualTo(new DockerSandboxProperties(
                                    "tcp://docker:2376",
                                    true,
                                    "/run/docker-certs",
                                    "runsc",
                                    "worker-id",
                                    "/usr/bin/docker"));
                    assertThat(context.getBean(SandboxGatewayProperties.class).port())
                            .isEqualTo(9081);
                    assertThat(context.getBean(SandboxProperties.class).maxConcurrentContainers())
                            .isEqualTo(3);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldConstructBothDockerClientsWithOrWithoutShippedYaml(boolean includeYaml) throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        runner.withInitializer(context -> {
                    if (includeYaml) {
                        sources.forEach(source ->
                                context.getEnvironment().getPropertySources().addLast(source));
                    }
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var docker = context.getBean(DockerSandboxProperties.class);
                    var sandbox = context.getBean(SandboxProperties.class);
                    assertThat(docker.host()).isEqualTo("unix:///var/run/docker.sock");
                    assertThat(docker.tlsVerify()).isFalse();
                    assertThat(docker.resolvedAppServerContainerId()).isNull();
                    var configuration = new DockerSandboxConfiguration();
                    assertThatCode(() -> {
                                configuration.dockerClient(sandbox, docker).close();
                                configuration
                                        .dockerStreamingClient(sandbox, docker)
                                        .close();
                            })
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void shouldBindDocumentedEnvironmentVariablesThroughShippedYaml() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        runner.withInitializer(context -> {
                    sources.forEach(source ->
                            context.getEnvironment().getPropertySources().addLast(source));
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(new SystemEnvironmentPropertySource(
                                    "sandbox-test-environment",
                                    Map.of(
                                            "SANDBOX_DOCKER_HOST", "tcp://docker:2376",
                                            "SANDBOX_DOCKER_TLS_VERIFY", "true",
                                            "SANDBOX_DOCKER_CERT_PATH", "/run/docker-certs",
                                            "SANDBOX_DOCKER_CONTAINER_RUNTIME", "runsc",
                                            "SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID", "worker-id",
                                            "SANDBOX_DOCKER_CLI", "/usr/bin/docker")));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DockerSandboxProperties.class))
                            .isEqualTo(new DockerSandboxProperties(
                                    "tcp://docker:2376",
                                    true,
                                    "/run/docker-certs",
                                    "runsc",
                                    "worker-id",
                                    "/usr/bin/docker"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"host=", "cli="})
    void shouldRejectBlankRequiredDockerSettings(String property) {
        runner.withPropertyValues("hephaestus.sandbox.docker." + property)
                .run(context ->
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRequireCertificatePathWhenTlsIsEnabled(@Nullable String certificatePath) {
        var tlsRunner = runner.withPropertyValues("hephaestus.sandbox.docker.tls-verify=true");
        if (certificatePath != null) {
            tlsRunner = tlsRunner.withPropertyValues("hephaestus.sandbox.docker.cert-path=" + certificatePath);
        }
        tlsRunner.run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void shouldRejectInvalidTlsBoolean() {
        runner.withPropertyValues("hephaestus.sandbox.docker.tls-verify=invalid")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasCauseInstanceOf(BindException.class)
                        .hasStackTraceContaining("tls-verify"));
    }

    @Test
    void shouldRejectUnknownDockerSettings() {
        runner.withPropertyValues("hephaestus.sandbox.docker.unknown-setting=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class));
    }

    @Test
    void shouldLeaveDockerUnboundWhenWorkerRoleIsDisabled() {
        isolatedRunner()
                .withUserConfiguration(DockerSandboxConfiguration.class, ScannedConfiguration.class)
                .withPropertyValues(RuntimeRole.WORKER_PROPERTY + "=false", "hephaestus.sandbox.docker.host=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(DockerSandboxProperties.class)
                            .doesNotHaveBean(DockerClient.class);
                });
    }

    @Test
    void shouldBindDockerWhenWorkerRoleIsEnabled() {
        isolatedRunner()
                .withUserConfiguration(ScannedConfiguration.class)
                .withPropertyValues(RuntimeRole.WORKER_PROPERTY + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DockerSandboxProperties.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = DockerSandboxProperties.class)
    static class ScannedConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        DockerSandboxProperties.class,
        SandboxProperties.class,
        SandboxGatewayProperties.class
    })
    static class PropertiesConfiguration {}
}
