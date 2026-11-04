package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsTraceContext;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsTraceContexts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamOptions;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Names every bean the webhook role contributes, because most of them have no other caller.
 * {@code webhookStreamMonitor} and {@code webhookPayloadCapacityCheck} are only ever reached through
 * their {@code @PostConstruct}: delete either factory method and every other test still passes, which
 * is the instrument that measures silent failure going missing silently.
 */
class WebhookProducerWiringTest extends BaseUnitTest {

    /** The beans an ingestion outage is invisible without. */
    private static final String[] WEBHOOK_ROLE_BEANS = {
        "webhookStreamMonitor",
        "webhookPayloadCapacityCheck",
        "webhookJetStreamBootstrap",
        "webhookHealthIndicator",
        "jetStreamPublisher",
        "webhookGracefulShutdown",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("natsConnection", Connection.class, WebhookProducerWiringTest::natsConnection)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(NatsTraceContext.class, NatsTraceContexts::none)
            .withBean(WebhookProperties.class, WebhookPropertiesFixture::properties)
            .withBean(
                    NatsConnectionProperties.class,
                    () -> new NatsConnectionProperties(
                            false, null, "hephaestus", new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))))
            .withUserConfiguration(WebhookConfiguration.class);

    @Test
    void contributesEveryBeanTheWebhookRoleIsResponsibleFor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String bean : WEBHOOK_ROLE_BEANS) {
                assertThat(context).hasBean(bean);
            }
        });
    }

    @Test
    void contributesNoneOfThemWhereTheRoleIsOff() {
        // The control: application-server runs with this false, so a bound delivered there configures
        // nothing. scripts/check-env-roles.mjs is the other half of that check.
        runner.withPropertyValues(RuntimeRole.WEBHOOK_PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            for (String bean : WEBHOOK_ROLE_BEANS) {
                assertThat(context).doesNotHaveBean(bean);
            }
        });
    }

    /** A connection whose JetStream handles answer "no such stream", so bootstrap creates rather than reads. */
    private static Connection natsConnection() {
        try {
            JetStreamApiException notFound = mock(JetStreamApiException.class);
            lenient().when(notFound.getErrorCode()).thenReturn(404);
            JetStreamManagement jsm = mock(JetStreamManagement.class);
            lenient().when(jsm.getStreamInfo(anyString())).thenThrow(notFound);
            Connection connection = mock(Connection.class);
            // Lenient throughout: the role-off case builds the same connection and touches none of it.
            lenient().when(connection.jetStreamManagement()).thenReturn(jsm);
            // The bootstrap takes its own handle with a longer request timeout than the probes want.
            lenient()
                    .when(connection.jetStreamManagement(any(JetStreamOptions.class)))
                    .thenReturn(jsm);
            lenient().when(connection.jetStream()).thenReturn(mock(JetStream.class));
            lenient().when(connection.getMaxPayload()).thenReturn(26_214_400L);
            return connection;
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException(e);
        }
    }
}
