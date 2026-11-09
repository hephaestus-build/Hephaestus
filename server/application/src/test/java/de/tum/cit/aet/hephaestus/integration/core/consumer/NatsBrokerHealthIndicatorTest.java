package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Connection;
import io.nats.client.api.ServerInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** A broker older than the minimum shows as a failing health component, so an operator sees why replays fail. */
class NatsBrokerHealthIndicatorTest extends BaseUnitTest {

    private final IntegrationConsumerStats stats = new IntegrationConsumerStats();
    private final NatsBrokerHealthIndicator health = new NatsBrokerHealthIndicator(stats);

    @Test
    void shouldReportDownWithTheVersionAndTheDocumentationWhenTheBrokerIsOlderThanTheMinimum() {
        consumer().observeBrokerVersion(broker("2.10.27", true));

        var report = health.health();

        assertThat(report.getStatus()).isEqualTo(Status.DOWN);
        assertThat(report.getDetails())
                .containsEntry("version", "2.10.27")
                .containsEntry("minimumVersion", "2.11.0")
                .containsEntry("documentation", IntegrationNatsConsumer.BROKER_REQUIREMENT_DOCS);
    }

    @Test
    void shouldReportUpWhenTheBrokerMeetsTheMinimum() {
        consumer().observeBrokerVersion(broker("2.14.9", false));

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void shouldReportUnknownBeforeAConnectionReportsTheVersion() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    private static Connection broker(String version, boolean older) {
        ServerInfo info = mock(ServerInfo.class);
        when(info.getVersion()).thenReturn(version);
        when(info.isOlderThanVersion(IntegrationNatsConsumer.MINIMUM_BROKER_VERSION))
                .thenReturn(older);
        Connection connection = mock(Connection.class);
        when(connection.getServerInfo()).thenReturn(info);
        return connection;
    }

    private IntegrationNatsConsumer consumer() {
        return new IntegrationNatsConsumer(
                new NatsConnectionProperties(
                        true,
                        "nats://localhost:4222",
                        "heph",
                        new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                NatsConsumerPropertiesFixture.defaults(),
                scopeId -> Optional.empty(),
                mock(IntegrationMessageDispatcher.class),
                mock(IntegrationPoisonHandler.class),
                stats,
                mock(ConnectionActivityRecorder.class),
                List.of(),
                NatsTraceContexts.none(),
                NatsTraceContexts.messageMeters(),
                mock(DeliveryInbox.class),
                event -> {});
    }
}
