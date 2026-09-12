package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/** Real SMTP on isolated, dynamically allocated ports; no external relay or credentials. */
@Tag("integration")
class EmailSmtpIntegrationTest {

    @Test
    void shouldDeliverThroughBootsSenderToAnSmtpInbox(TestReporter reporter) {
        var registry = new SimpleMeterRegistry();
        try (var mailpit = new GenericContainer<>(
                        "axllent/mailpit:v1.31.1@sha256:98b916bd3c8d61f7633a52d3ea2f58d00620cb01ca57ab59edde68c347a95365")
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/readyz").forPort(8025))) {
            mailpit.start();
            new ApplicationContextRunner()
                    .withUserConfiguration(EmailTransportConfiguration.class)
                    .withPropertyValues(
                            "spring.mail.host=" + mailpit.getHost(),
                            "spring.mail.port=" + mailpit.getMappedPort(1025),
                            "spring.mail.properties[mail.smtp.connectiontimeout]=5000",
                            "spring.mail.properties[mail.smtp.timeout]=10000",
                            "spring.mail.properties[mail.smtp.writetimeout]=10000")
                    .run(context -> {
                        var gateway = new EmailGateway(
                                Optional.of(context.getBean(JavaMailSender.class)),
                                new EmailProperties("noreply@hephaestus.test", "Hephaestus", null),
                                new OutboundEgressGuard(() -> false),
                                new EmailDeliveryMetrics(registry));
                        var result = gateway.send(EmailMessage.of(
                                EmailKind.TEST_MESSAGE,
                                "developer@hephaestus.test",
                                EmailTestSupport.renderer().render(EmailKind.TEST_MESSAGE, java.util.Map.of())));
                        assertThat(result.outcome()).isEqualTo(EmailDeliveryResult.Outcome.SENT);

                        String inboxUrl = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
                        reporter.publishEntry("Mailpit inbox", inboxUrl);
                        reporter.publishEntry(
                                "Mailpit SMTP port", mailpit.getMappedPort(1025).toString());
                        try (var client = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build()) {
                            var response = client.send(
                                    HttpRequest.newBuilder(URI.create(inboxUrl + "/api/v1/messages"))
                                            .timeout(Duration.ofSeconds(10))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                            assertThat(response.statusCode()).isEqualTo(200);
                            var inbox = JsonMapper.builder().build().readTree(response.body());
                            assertThat(inbox.path("total").asInt()).isEqualTo(1);
                            var message = inbox.path("messages").path(0);
                            assertThat(message.path("To")
                                            .path(0)
                                            .path("Address")
                                            .asString())
                                    .isEqualTo("developer@hephaestus.test");
                            assertThat(message.path("MessageID").asString())
                                    .isEqualTo(java.util.Objects.requireNonNull(result.messageId())
                                            .replace("<", "")
                                            .replace(">", ""));
                        }
                    });
        } finally {
            registry.close();
        }
    }
}
