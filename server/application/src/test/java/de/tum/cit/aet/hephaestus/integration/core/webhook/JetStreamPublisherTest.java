package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher.Cause;
import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher.PublishFailedException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * One publish deadline: a late ack is awaited, the client giving up on an ack or the broker refusing at once is
 * retried with the same {@code Nats-Msg-Id}, a stream's refusal is final, and every failure names its cause.
 */
class JetStreamPublisherTest extends BaseUnitTest {

    private static final Duration DEADLINE = Duration.ofMillis(400);

    private static final PublishRequest REQUEST =
            new PublishRequest("github.org.repo.push", "github-x", Map.of("Nats-Msg-Id", "github-x"), new byte[] {1});

    private final WebhookProperties properties = new WebhookProperties(
            null,
            null,
            new WebhookProperties.TokenRotation(7, 90),
            new WebhookProperties.Publish(DEADLINE, Duration.ofMillis(10)),
            WebhookPropertiesFixture.stream(),
            new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
            new WebhookProperties.Http(26_214_400L),
            new WebhookProperties.Routing(null, null));

    private final JetStream jetStream = mock(JetStream.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JetStreamPublisher publisher = new JetStreamPublisher(jetStream, properties, meters);

    @Test
    void shouldStoreTheDeliveryWhenTheBrokerFirstHasNoResponders() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(failed(noResponders()))
                .thenReturn(stored());

        publisher.publish(REQUEST);

        verify(jetStream, times(2))
                .publishAsync(any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class));
        assertThat(published("github", "success")).isEqualTo(1);
        assertThat(meters.get("webhook.publish.retry").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldPublishAgainWhenTheClientStopsWaitingForTheAck() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(failed(new CancellationException("timed out")))
                .thenReturn(stored());

        publisher.publish(REQUEST);

        assertThat(published("github", "success")).isEqualTo(1);
        assertThat(meters.get("webhook.publish.retry").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldRetryWhileTheReconnectBufferIsFull() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenThrow(new IllegalStateException("Unable to queue any more messages during reconnect"))
                .thenReturn(stored());

        publisher.publish(REQUEST);

        assertThat(published("github", "success")).isEqualTo(1);
    }

    @Test
    void shouldCountAndNameAMessageTheClientRejects() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenThrow(new IllegalArgumentException("Message payload size exceeded server configuration"));

        assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOfSatisfying(PublishFailedException.class, e -> {
            assertThat(e.failureCause()).isEqualTo(Cause.REJECTED);
            assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(published("github", "failure")).isEqualTo(1);
    }

    @Test
    void shouldNameAnInterruptAndKeepTheThreadInterrupted() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> publisher.publish(REQUEST))
                    .isInstanceOfSatisfying(
                            PublishFailedException.class,
                            e -> assertThat(e.failureCause()).isEqualTo(Cause.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldAnswerWithinTheDeadlineWhenTheAckNeverArrives() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(new CompletableFuture<>());
        long started = System.nanoTime();

        assertThatThrownBy(() -> publisher.publish(REQUEST))
                .isInstanceOfSatisfying(
                        PublishFailedException.class,
                        e -> assertThat(e.failureCause()).isEqualTo(Cause.TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(DEADLINE)
                .isLessThan(DEADLINE.plusSeconds(2));
        assertThat(published("github", "failure")).isEqualTo(1);
    }

    @Test
    void shouldNameNoRespondersWhenTheBrokerNeverAnswersWithinTheDeadline() {
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenAnswer(invocation -> failed(noResponders()));

        assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOfSatisfying(PublishFailedException.class, e -> {
            assertThat(e.failureCause()).isEqualTo(Cause.NO_RESPONDERS);
            assertThat(e.attempts()).isGreaterThan(1);
        });
    }

    @Test
    void shouldNotRetryWhenTheStreamIsFull() {
        JetStreamApiException full = mock(JetStreamApiException.class);
        when(full.getErrorDescription()).thenReturn("maximum messages exceeded");
        when(full.getApiErrorCode()).thenReturn(10077);
        // The client rethrows the broker's refusal wrapped in a RuntimeException.
        CompletableFuture<PublishAck> refused = failed(new RuntimeException(full));
        when(jetStream.publishAsync(
                        any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(refused);

        assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOfSatisfying(PublishFailedException.class, e -> {
            assertThat(e.failureCause()).isEqualTo(Cause.STREAM_FULL);
            assertThat(e.attempts()).isEqualTo(1);
        });
        verify(jetStream, times(1))
                .publishAsync(any(String.class), any(Headers.class), any(byte[].class), any(PublishOptions.class));
    }

    @Test
    void shouldCountAndNameASubjectOutsideTheKnownStreamsWithoutPublishing() {
        PublishRequest unroutable = new PublishRequest("nodot", "x", Map.of(), new byte[] {1});

        assertThatThrownBy(() -> publisher.publish(unroutable))
                .isInstanceOfSatisfying(
                        PublishFailedException.class,
                        e -> assertThat(e.failureCause()).isEqualTo(Cause.REJECTED));
        assertThat(published("other", "failure")).isEqualTo(1);
    }

    @Test
    void shouldTagASubjectOutsideTheKnownStreamsAsOther() {
        assertThat(JetStreamPublisher.streamTag("slack.team.message")).isEqualTo("slack");
        assertThat(JetStreamPublisher.streamTag("unknown.x")).isEqualTo("other");
        assertThat(JetStreamPublisher.streamTag("nodot")).isEqualTo("other");
    }

    /** What the client's publish future fails with when no stream listens: its IOException, wrapped. */
    private static RuntimeException noResponders() {
        return new RuntimeException(new IOException("Error Publishing: 503 No Responders Available For Request"));
    }

    private static CompletableFuture<PublishAck> stored() {
        return CompletableFuture.completedFuture(mock(PublishAck.class));
    }

    private static CompletableFuture<PublishAck> failed(Throwable cause) {
        return CompletableFuture.failedFuture(cause);
    }

    private double published(String stream, String outcome) {
        return meters.get("webhook.publish")
                .tag("stream", stream)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
