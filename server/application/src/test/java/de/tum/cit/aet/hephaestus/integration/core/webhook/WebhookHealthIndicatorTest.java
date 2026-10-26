package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Connection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class WebhookHealthIndicatorTest extends BaseUnitTest {

    @Test
    void shouldReportUpFromTheConnectionStateAloneWhenConnected() {
        Connection connection = mock(Connection.class);
        when(connection.getStatus()).thenReturn(Connection.Status.CONNECTED);

        Health health = new WebhookHealthIndicator(connection).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of("natsStatus", "CONNECTED"));
    }

    @Test
    void shouldReportDownWhenTheConnectionIsNotConnected() {
        Connection connection = mock(Connection.class);
        when(connection.getStatus()).thenReturn(Connection.Status.DISCONNECTED);

        Health health = new WebhookHealthIndicator(connection).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("natsStatus", "DISCONNECTED");
    }
}
