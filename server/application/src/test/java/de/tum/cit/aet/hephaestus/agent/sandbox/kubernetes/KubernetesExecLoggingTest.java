package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The Kubernetes client puts each argument of an exec into the URL of its WebSocket and logs that URL when the upgrade
 * fails. The mock API server answers no exec upgrade, so the real client fails it; the command carries only a
 * synthetic argument. Under the shipped {@code logging.level} the client's logger records nothing, and the runtime's
 * own event still records the failure, by class and status.
 */
@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesExecLoggingTest {

    private static final String CATEGORY = "io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl";
    private static final String SENTINEL = "synthetic-argument-4f1c9e";
    private static final String TURNS = "hephaestus-sandboxes";

    private @Nullable KubernetesClient client;

    @Test
    void shouldKeepCommandArgumentsOutOfTheLogWhenAnExecUpgradeFails() throws IOException {
        KubernetesClient api = Objects.requireNonNull(client, "the mock server injects the client");
        var worker = KubernetesFixtures.worker(TURNS);
        var pods = new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT);
        String sandbox = "hephaestus-turn-" + UUID.randomUUID();
        api.pods().inNamespace(TURNS).resource(runningTurnPod(sandbox)).create();
        var command = new ExecSandboxes.Command(List.of("echo", SENTINEL), Duration.ofSeconds(5), 1024);

        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        String owner = KubernetesExecSandboxes.class.getName();
        ListAppender<ILoggingEvent> clientEvents = attach(CATEGORY);
        ListAppender<ILoggingEvent> ownedEvents = attach(owner);
        try (var sandboxes = new KubernetesExecSandboxes(
                pods,
                worker,
                "agent",
                KubernetesFixtures.live(KubernetesFixtures.WORKER_ID),
                new SandboxExits(pods, new SimpleMeterRegistry()),
                mock(KubernetesSandboxCache.class))) {
            logging.setLogLevel(owner, LogLevel.WARN);

            // Without the shipped entry the client's logger inherits an ordinary level and writes the URL.
            logging.setLogLevel(CATEGORY, LogLevel.INFO);
            assertThatThrownBy(() -> sandboxes.exec(sandbox, command))
                    .isInstanceOf(SandboxInfrastructureException.class);
            assertThat(clientEvents.list)
                    .map(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> {
                        assertThat(message).contains("command=" + SENTINEL);
                        assertThat(message.indexOf("prlimit")).isBetween(0, message.indexOf(SENTINEL));
                    });

            clientEvents.list.clear();
            ownedEvents.list.clear();
            logging.setLogLevel(CATEGORY, shippedLevels().get(CATEGORY));
            assertThatThrownBy(() -> sandboxes.exec(sandbox, command))
                    .isInstanceOf(SandboxInfrastructureException.class);

            assertThat(clientEvents.list).isEmpty();
            assertThat(ownedEvents.list).anySatisfy(event -> {
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("event.name");
                    assertThat(pair.value).isEqualTo("sandbox.exec.failed");
                });
                assertThat(event.getKeyValuePairs())
                        .noneSatisfy(
                                pair -> assertThat(String.valueOf(pair.value)).contains(SENTINEL));
                assertThat(event.getFormattedMessage()).doesNotContain(SENTINEL);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logging.setLogLevel(CATEGORY, null);
            logging.setLogLevel(owner, null);
            detach(CATEGORY, clientEvents);
            detach(owner, ownedEvents);
        }
    }

    /** The {@code logging.level} entries of the shipped configuration, bound as Spring Boot binds them at startup. */
    private static Map<String, LogLevel> shippedLevels() throws IOException {
        PropertySource<?> shared = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();
        ConfigurationPropertySource source =
                Objects.requireNonNull(ConfigurationPropertySource.from(shared), "application.yml binds as properties");
        return new Binder(source)
                .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElse(Map.of());
    }

    private static Pod runningTurnPod(String name) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(TURNS)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(KubernetesPods.SANDBOX)
                .withImage("agent")
                .endContainer()
                .addNewContainer()
                .withName(KubernetesPods.STAGER)
                .withImage("agent")
                .endContainer()
                .endSpec()
                .withNewStatus()
                .withPhase("Running")
                .addNewCondition()
                .withType("Ready")
                .withStatus("True")
                .endCondition()
                .withContainerStatuses(running(KubernetesPods.SANDBOX), running(KubernetesPods.STAGER))
                .endStatus()
                .build();
    }

    private static ContainerStatus running(String container) {
        return new ContainerStatusBuilder()
                .withName(container)
                .withReady(true)
                .withNewState()
                .withNewRunning()
                .endRunning()
                .endState()
                .build();
    }

    private static ListAppender<ILoggingEvent> attach(String category) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(category)).addAppender(appender);
        return appender;
    }

    private static void detach(String category, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(category)).detachAppender(appender);
        appender.stop();
    }
}
