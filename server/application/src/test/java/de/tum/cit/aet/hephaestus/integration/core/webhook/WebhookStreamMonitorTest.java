package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture.GIBIBYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.SequenceInfo;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamState;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class WebhookStreamMonitorTest extends BaseUnitTest {

    private static final String STREAM = "github";
    private static final String DURABLE_BASE = "hephaestus";
    /** Built the way IntegrationNatsConsumer builds it, so the two cannot drift apart unnoticed. */
    private static final String CONSUMER = scope(1);

    private final WebhookProperties properties = WebhookPropertiesFixture.properties();
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final JetStreamManagement jsm = mock(JetStreamManagement.class);

    @Test
    void claimsNoLossWhenOtherSubjectsPushTheStreamPastCaughtUpFilteredConsumers(CapturedOutput output)
            throws Exception {
        // Both durables are caught up on their own subjects; a busier organisation's messages, shed at the
        // byte bound, moved the stream's first sequence past ack floors that only move on a match.
        WebhookStreamMonitor monitor = monitor();
        give(1_000_000, caughtUp(scope(5), 4_200), caughtUp(scope(8), 17_000));
        monitor.poll();
        give(1_100_000, caughtUp(scope(5), 4_200), caughtUp(scope(8), 17_000));
        monitor.poll();

        assertThat(output.getAll())
                .as("a sequence below firstSequence says nothing about a filtered consumer's own subjects")
                .doesNotContain("ERROR");
        assertThat(pending()).isZero();
        assertThat(ackPending()).isZero();
    }

    @Test
    void publishesTheBacklogThisDeploymentHasNotProcessedYet() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(
                1_000,
                consumer(scope(1), 900, 40, 3, 1),
                consumer(scope(2), 950, 2, 0, 0),
                // Another deployment's durable on the same broker.
                consumer("pr-1234-appserver-consumer-scope-1-github", 10, 500, 0, 0));

        monitor.poll();

        assertThat(pending()).isEqualTo(42d);
        assertThat(ackPending()).isEqualTo(3d);
        assertThat(withoutPullRequests()).isEqualTo(1d);
    }

    @Test
    void followsTheDurablesThatExistNowOnOneSeriesPerStream() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, consumer(scope(2), 800, 30, 0, 0), consumer(scope(1), 900, 5, 0, 1));
        monitor.poll();

        // Scope 2 is gone and scope 3 is new. A tag per consumer would leave scope 2's backlog standing
        // on a series of its own forever; one series per stream has to follow the set that exists now.
        give(1_100, consumer(scope(3), 1_050, 7, 0, 1));
        monitor.poll();

        assertThat(pending()).isEqualTo(7d);
        assertThat(registry.find("webhook.stream.consumer.pending").gauges())
                .hasSize(WebhookJetStreamBootstrap.STREAMS.length)
                .allSatisfy(
                        gauge -> assertThat(gauge.getId().getTag("consumer")).isNull());
    }

    @Test
    void publishesStreamUsageAlongsideIt() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, caughtUp(CONSUMER, 999));

        monitor.poll();

        assertThat(gauge("webhook.stream.bytes")).isEqualTo((double) GIBIBYTE / 2);
        assertThat(gauge("webhook.stream.bytes.utilization")).isEqualTo(0.5);
    }

    @Test
    void publishesTheAgeOfTheOldestStoredMessageAsEffectiveRetention() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, ZonedDateTime.now().minusDays(9), caughtUp(CONSUMER, 999));

        monitor.poll();

        assertThat(gauge("webhook.stream.oldest.message.age"))
                .as("max-age is a ceiling and max-bytes a floor; this is the retention the deployment gets")
                .isCloseTo(Duration.ofDays(9).toSeconds(), within(60d));
    }

    @Test
    void saysSoWhenMonitoringItselfStopsWorking(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, consumer(CONSUMER, 900, 40, 0, 1));
        monitor.poll();
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getConsumers(STREAM);

        monitor.poll();

        assertThat(pending())
                .as("a broker blip must not read as a backlog that drained")
                .isEqualTo(40d);
        assertThat(output.getAll())
                .as("a held gauge reads exactly like a current one, so the failure has to say so itself")
                .contains("its gauges hold their last values");
    }

    @Test
    void reportsRecoveryAndOnlyTheFirstOfARunOfFailures(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, caughtUp(CONSUMER, 999));
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getStreamInfo(STREAM);
        monitor.poll();
        monitor.poll();
        give(1_000, caughtUp(CONSUMER, 999));

        monitor.poll();

        assertThat(output.getAll().split("hold their last values", -1))
                .as("one line per outage, not one per poll")
                .hasSize(2);
        assertThat(output.getAll()).contains("Webhook stream monitoring resumed for stream github");
    }

    @Test
    void reportsHowStaleTheGaugesAre() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        assertThat(pollAge())
                .as("never polled is not the same as polled and found nothing")
                .isNaN();

        give(1_000, caughtUp(CONSUMER, 999));
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getStreamInfo(STREAM);
        monitor.poll();
        assertThat(pollAge())
                .as("a poll that failed did not refresh the gauges, so it must not say it did")
                .isNaN();

        give(1_000, caughtUp(CONSUMER, 999));
        monitor.poll();

        assertThat(pollAge()).isLessThan(5d);
    }

    @Test
    void stopWaitsForTheActivePollToFinish() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, caughtUp(CONSUMER, 999));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch blockUntilShutdown = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finishPoll = new CountDownLatch(1);
        CountDownLatch pollFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entered.countDown();
                    try {
                        blockUntilShutdown.await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        finishPoll.await();
                    }
                    pollFinished.countDown();
                    return quiet();
                })
                .when(jsm)
                .getStreamInfo(STREAM);
        monitor.start();
        try (var stopping = Executors.newSingleThreadExecutor()) {
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var stopped = stopping.submit(monitor::stop);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> stopped.get(100, TimeUnit.MILLISECONDS))
                        .as("shutdown waits while the interrupted broker poll is still finishing")
                        .isInstanceOf(TimeoutException.class);
                finishPoll.countDown();
                stopped.get(5, TimeUnit.SECONDS);
                assertThat(pollFinished.getCount()).isZero();
            } finally {
                finishPoll.countDown();
                blockUntilShutdown.countDown();
                monitor.stop();
            }
        }
    }

    private WebhookStreamMonitor monitor() {
        return new WebhookStreamMonitor(jsm, properties, DURABLE_BASE, registry);
    }

    /** Puts the stream at {@code firstSequence} with exactly the consumers given. */
    private void give(long firstSequence, ConsumerInfo... consumers) throws Exception {
        give(firstSequence, ZonedDateTime.now(), consumers);
    }

    private void give(long firstSequence, ZonedDateTime firstTime, ConsumerInfo... consumers) throws Exception {
        StreamConfiguration config = StreamConfiguration.builder()
                .name(STREAM)
                .subjects(STREAM + ".>")
                .maxBytes(GIBIBYTE)
                .build();
        StreamState state = mock(StreamState.class);
        lenient().when(state.getByteCount()).thenReturn(GIBIBYTE / 2);
        lenient().when(state.getMsgCount()).thenReturn(1_000L);
        lenient().when(state.getConsumerCount()).thenReturn(1L);
        lenient().when(state.getFirstSequence()).thenReturn(firstSequence);
        lenient().when(state.getFirstTime()).thenReturn(firstTime);
        StreamInfo info = mock(StreamInfo.class);
        lenient().when(info.getConfiguration()).thenReturn(config);
        lenient().when(info.getStreamState()).thenReturn(state);
        // The monitor sweeps all four streams; the other three stay quiet so only what a test sets
        // up can move a meter, and only what a test breaks can fail a poll.
        doReturn(quiet()).when(jsm).getStreamInfo(anyString());
        doReturn(List.of()).when(jsm).getConsumers(anyString());
        doReturn(info).when(jsm).getStreamInfo(STREAM);
        doReturn(List.of(consumers)).when(jsm).getConsumers(STREAM);
    }

    private static StreamInfo quiet() {
        StreamState state = mock(StreamState.class);
        lenient().when(state.getFirstSequence()).thenReturn(1L);
        StreamInfo info = mock(StreamInfo.class);
        lenient()
                .when(info.getConfiguration())
                .thenReturn(StreamConfiguration.builder().name("quiet").build());
        lenient().when(info.getStreamState()).thenReturn(state);
        return info;
    }

    private static String scope(long scopeId) {
        return ConsumerSubjectMath.scopeConsumerName(DURABLE_BASE, scopeId) + "-github";
    }

    /** A durable with a pull request waiting and nothing matching its filter left to read. */
    private static ConsumerInfo caughtUp(String name, long ackFloor) {
        return consumer(name, ackFloor, 0, 0, 1);
    }

    private static ConsumerInfo consumer(
            String name, long ackFloor, long pending, long ackPending, long waitingPullRequests) {
        SequenceInfo floor = mock(SequenceInfo.class);
        lenient().when(floor.getStreamSequence()).thenReturn(ackFloor);
        ConsumerInfo consumer = mock(ConsumerInfo.class);
        lenient().when(consumer.getName()).thenReturn(name);
        lenient().when(consumer.getAckFloor()).thenReturn(floor);
        lenient().when(consumer.getNumPending()).thenReturn(pending);
        lenient().when(consumer.getNumAckPending()).thenReturn(ackPending);
        lenient().when(consumer.getNumWaiting()).thenReturn(waitingPullRequests);
        return consumer;
    }

    private double pending() {
        return gauge("webhook.stream.consumer.pending");
    }

    private double ackPending() {
        return gauge("webhook.stream.consumer.ack.pending");
    }

    private double withoutPullRequests() {
        return gauge("webhook.stream.consumers.without.pull.requests");
    }

    private double pollAge() {
        return gauge("webhook.stream.poll.age");
    }

    private double gauge(String name) {
        return registry.get(name).tag("stream", STREAM).gauge().value();
    }
}
