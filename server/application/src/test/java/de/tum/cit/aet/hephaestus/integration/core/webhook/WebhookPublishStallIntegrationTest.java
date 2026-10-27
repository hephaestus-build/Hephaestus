package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.PublishOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A broker that stops answering, as one on a saturated disk does: the receiver answers {@code 503} before GitHub
 * stops waiting (10 seconds), and its health indicator, which asks the broker nothing, stays UP. Its own broker,
 * because the test pauses it.
 */
class WebhookPublishStallIntegrationTest extends BaseIntegrationTest {

    /** GitHub fails a delivery that gets no answer in this time, and never retries it itself. */
    private static final Duration GITHUB_RESPONSE_WINDOW = Duration.ofSeconds(10);

    /** The publish settings a deployment binds from application.yml. */
    @Autowired
    private WebhookProperties properties;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private GenericContainer<?> broker;
    private Connection connection;

    @BeforeEach
    @SuppressWarnings("resource") // stopped in stopBroker
    void startBroker() throws Exception {
        broker = new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
                .withCommand("-js")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        broker.start();
        connection = Nats.connect("nats://" + broker.getHost() + ":" + broker.getMappedPort(4222));
        connection
                .jetStreamManagement()
                .addStream(StreamConfiguration.builder()
                        .name("github")
                        .subjects("github.>")
                        .storageType(StorageType.File)
                        .duplicateWindow(properties.stream().duplicateWindow())
                        .build());
    }

    @AfterEach
    void stopBroker() throws InterruptedException {
        if (connection != null) connection.close();
        if (broker != null) broker.stop();
    }

    @Test
    void shouldAnswer503BeforeGitHubStopsWaitingWhileTheBrokerStalls() throws IOException {
        WebhookIngestPipeline pipeline = pipeline();
        WebhookHealthIndicator health = new WebhookHealthIndicator(connection);
        assertThat(deliver(pipeline, "first").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        try {
            CompletableFuture<ResponseEntity<?>> answer =
                    CompletableFuture.supplyAsync(() -> deliver(pipeline, "during-the-stall"));

            await().atMost(GITHUB_RESPONSE_WINDOW).until(answer::isDone);
            assertThat(answer.join().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        } finally {
            broker.getDockerClient()
                    .unpauseContainerCmd(broker.getContainerId())
                    .exec();
        }
    }

    @Test
    void shouldStoreADeliveryWhenTheStreamReturnsWithinTheDeadline() throws Exception {
        WebhookIngestPipeline pipeline = pipeline();
        StreamConfiguration github =
                connection.jetStreamManagement().getStreamInfo("github").getConfiguration();
        connection.jetStreamManagement().deleteStream("github");

        // No stream listens: the broker answers every attempt at once with "no responders".
        CompletableFuture<ResponseEntity<?>> answer = CompletableFuture.supplyAsync(() -> deliver(pipeline, "returns"));
        // The receiver is retrying: let it fail at least twice before the stream returns.
        await().atMost(Duration.ofSeconds(5))
                .until(() -> meters.get("webhook.publish.retry").counter().count() >= 2);
        connection.jetStreamManagement().addStream(github);

        await().atMost(GITHUB_RESPONSE_WINDOW).until(answer::isDone);
        assertThat(answer.join().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(connection
                        .jetStreamManagement()
                        .getStreamInfo("github")
                        .getStreamState()
                        .getMsgCount())
                .isEqualTo(1);
    }

    @Test
    void shouldStoreOneCopyWhenADeliveryStoredBeforeIsPublishedAgain() throws Exception {
        // An earlier attempt the broker stored but whose ack never reached the receiver.
        Headers headers = new Headers().add("X-GitHub-Delivery", "acked-late");
        connection
                .jetStream()
                .publish(
                        "github.acme.repo.pull_request",
                        headers,
                        "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8),
                        PublishOptions.builder().messageId("github-acked-late").build());

        assertThat(deliver(pipeline(), "acked-late").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(connection
                        .jetStreamManagement()
                        .getStreamInfo("github")
                        .getStreamState()
                        .getMsgCount())
                .isEqualTo(1);
    }

    private WebhookIngestPipeline pipeline() throws IOException {
        JetStreamPublisher publisher = new JetStreamPublisher(connection.jetStream(), properties, meters);
        return new WebhookIngestPipeline(
                List.of(new AcceptingVerifier()), List.of(new FixedSubject()), publisher, new ObjectMapper());
    }

    private static ResponseEntity<?> deliver(WebhookIngestPipeline pipeline, String delivery) {
        return pipeline.handle(
                IntegrationKind.GITHUB,
                "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("X-GitHub-Event", "pull_request", "X-GitHub-Delivery", delivery));
    }

    private static final class AcceptingVerifier implements WebhookSignatureVerifier {

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        @Override
        public VerificationResult verify(WebhookRequest request) {
            return new VerificationResult.Verified();
        }
    }

    private static final class FixedSubject implements SubjectKeyDeriver {

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        @Override
        public String deriveSubject(JsonNode payload, Map<String, String> headers) {
            return "github.acme.repo.pull_request";
        }

        @Override
        public String deriveDedupKey(byte[] body, Map<String, String> headers) {
            return "github-" + headers.get("X-GitHub-Delivery");
        }
    }
}
