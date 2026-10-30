package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class IngestDurabilityIntegrationTest {
    @Test
    @SuppressWarnings("try") // NATS close can be interrupted; the test propagates interruption.
    void shouldPreserveTheFailedHeadThroughPostgresOutageAndConsumerRestart() throws Exception {
        // This database is private to the test: never pause the shared context's PostgreSQL container.
        try (var postgres = new PostgreSQLContainer("postgres:18-alpine");
                Connection connection = Nats.connect(NatsTestContainer.getServerUrl())) {
            postgres.start();
            String url = postgres.getJdbcUrl() + "?connectTimeout=1&socketTimeout=1";
            try (var sql = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                    var statement = sql.createStatement()) {
                statement.execute("CREATE TABLE received (position BIGSERIAL PRIMARY KEY, event TEXT UNIQUE)");
            }
            AtomicInteger failures = new AtomicInteger();
            List<String> attempted = new CopyOnWriteArrayList<>();
            IntegrationMessageHandler handler = new IntegrationMessageHandler() {
                @Override
                public EventTypeKey key() {
                    return new EventTypeKey(IntegrationKind.GITHUB, "repository.issues");
                }

                @Override
                public void onMessage(Message message) {
                    String value = new String(message.getData(), StandardCharsets.UTF_8);
                    attempted.add(value);
                    try (var sql = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                            var statement = sql.prepareStatement(
                                    "INSERT INTO received(event) VALUES (?) ON CONFLICT DO NOTHING")) {
                        statement.setString(1, value);
                        statement.executeUpdate();
                    } catch (SQLException failure) {
                        failures.incrementAndGet();
                        throw new IllegalStateException("PostgreSQL unavailable", failure);
                    }
                }
            };
            String stream = "durability_" + UUID.randomUUID().toString().replace("-", "");
            connection
                    .jetStreamManagement()
                    .addStream(StreamConfiguration.builder()
                            .name(stream)
                            .subjects(stream + ".>")
                            .storageType(StorageType.File)
                            .build());
            var properties = new NatsConsumerProperties(
                    Duration.ofMillis(100),
                    Duration.ofMillis(20),
                    Duration.ZERO,
                    new NatsConsumerProperties.PoisonProperties(Duration.ofMillis(20), Duration.ofMillis(50)));
            var context = connection.getStreamContext(stream);
            var durable = context.createOrUpdateConsumer(IntegrationNatsConsumer.newConsumerConfiguration(
                    new String[] {stream + ".>"}, properties, "ordered"));
            IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
            when(dispatcher.dispatch(anyString())).thenReturn(Optional.of(handler));
            var fleet = new IntegrationNatsConsumer(
                    new NatsConnectionProperties(
                            true,
                            "nats://localhost:4222",
                            "test",
                            new NatsConnectionProperties.Consumer(Duration.ofSeconds(5))),
                    properties,
                    id -> Optional.empty(),
                    dispatcher,
                    new IntegrationPoisonHandler(
                            properties, new SimpleMeterRegistry(), mock(ConsumerDeadLetterService.class)),
                    new IntegrationConsumerStats(),
                    mock(ConnectionActivityRecorder.class),
                    List.of());
            ScopeConsumer consumer = new ScopeConsumer(
                    42L,
                    "ordered",
                    stream,
                    durable,
                    context,
                    new String[] {stream + ".>"},
                    message -> fleet.handleMessage(42L, message));
            boolean paused = false;
            try {
                postgres.getDockerClient()
                        .pauseContainerCmd(postgres.getContainerId())
                        .exec();
                paused = true;
                consumer.start();
                connection.jetStream().publish(stream + ".issues", "first".getBytes(StandardCharsets.UTF_8));
                connection.jetStream().publish(stream + ".issues", "second".getBytes(StandardCharsets.UTF_8));
                // Cross the previous poison threshold against a genuinely unreachable PostgreSQL server.
                await().atMost(Duration.ofSeconds(45)).until(() -> failures.get() >= 12);
                assertThat(attempted).containsOnly("first");
                consumer.stop();
                consumer = new ScopeConsumer(
                        42L,
                        "ordered",
                        stream,
                        context.getConsumerContext("ordered"),
                        context,
                        new String[] {stream + ".>"},
                        message -> fleet.handleMessage(42L, message));
                consumer.start();
                postgres.getDockerClient()
                        .unpauseContainerCmd(postgres.getContainerId())
                        .exec();
                paused = false;
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    try (var sql = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                            var statement = sql.createStatement();
                            var rows = statement.executeQuery("SELECT event FROM received ORDER BY position")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("first");
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("second");
                        assertThat(rows.next()).isFalse();
                    }
                });
                assertThat(durable.getConsumerInfo().getConsumerConfiguration().getMaxDeliver())
                        .isEqualTo(-1);
            } finally {
                if (paused)
                    postgres.getDockerClient()
                            .unpauseContainerCmd(postgres.getContainerId())
                            .exec();
                consumer.stop();
                fleet.shutdown();
                connection.jetStreamManagement().deleteStream(stream);
            }
        }
    }
}
