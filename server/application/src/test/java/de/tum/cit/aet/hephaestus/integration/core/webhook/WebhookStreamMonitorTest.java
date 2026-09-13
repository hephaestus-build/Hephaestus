package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture.GIBIBYTE;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class WebhookStreamMonitorTest extends BaseUnitTest {

    private static final String STREAM = "github";
    private static final String DURABLE_BASE = "hephaestus";
    /** Built the way IntegrationNatsConsumer builds it, so the two cannot drift apart unnoticed. */
    private static final String CONSUMER = ConsumerSubjectMath.scopeConsumerName(DURABLE_BASE, 1) + "-github";

    private final WebhookProperties properties = WebhookPropertiesFixture.properties();
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final JetStreamManagement jsm = mock(JetStreamManagement.class);

    @Test
    void countsMessagesTheStreamDeletedBeforeTheConsumerReadThem(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        // First poll establishes where the stream starts; nothing is charged for history.
        give(1_000, 900);
        monitor.poll();

        // The stream discarded 1000..1099 while the consumer's ack floor stayed at 900.
        give(1_100, 900);
        monitor.poll();

        assertThat(counter()).isEqualTo(100d);
        assertThat(output.getAll()).contains("deleted 100 unacknowledged webhook(s)");
    }

    @Test
    void countsNothingWhenTheConsumerKeptUpWithWhatWasDeleted() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, 999);
        monitor.poll();

        give(1_100, 1_099);
        monitor.poll();

        assertThat(counter()).isZero();
        assertThat(gauge()).isZero();
    }

    @Test
    void countsOnlyTheLossThatIsNewSinceTheLastPoll() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, 900);
        monitor.poll();
        give(1_100, 900);
        monitor.poll();

        // The stream has not moved, so the same 100 messages must not be charged twice.
        give(1_100, 900);
        monitor.poll();

        assertThat(counter()).isEqualTo(100d);
    }

    @Test
    void reportsALossThatHappenedBeforeThisProcessStartedWithoutCountingIt(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, 500);

        monitor.poll();

        assertThat(counter())
                .as("re-charging historic loss on every restart would make it meaningless")
                .isZero();
        assertThat(gauge()).isEqualTo(499d);
        assertThat(output.getAll()).contains("499 webhook(s) were deleted before it read them");
    }

    @Test
    void reportsTheStandingGapAsItGrows() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, 900);
        monitor.poll();
        give(1_500, 900);
        monitor.poll();

        assertThat(gauge()).isEqualTo(599d);
    }

    @Test
    void publishesStreamUsageAlongsideIt() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, 999);

        monitor.poll();

        assertThat(registry.get("webhook.stream.bytes")
                        .tag("stream", STREAM)
                        .gauge()
                        .value())
                .isEqualTo((double) GIBIBYTE / 2);
        assertThat(registry.get("webhook.stream.bytes.utilization")
                        .tag("stream", STREAM)
                        .gauge()
                        .value())
                .isEqualTo(0.5);
    }

    @Test
    void chargesLossOnlyToDurablesThisDeploymentOwns(CapturedOutput output) throws Exception {
        // Another deployment's durable on the same broker, permanently behind firstSequence.
        WebhookStreamMonitor monitor = monitor();
        give(1_000, consumer(CONSUMER, 999), consumer("pr-1234-appserver-consumer-scope-1-github", 500));
        monitor.poll();

        give(1_100, consumer(CONSUMER, 1_099), consumer("pr-1234-appserver-consumer-scope-1-github", 500));
        monitor.poll();

        assertThat(counter()).isZero();
        assertThat(gauge()).isZero();
        assertThat(output.getAll()).doesNotContain("pr-1234-appserver-consumer");
    }

    @Test
    void reportsTheWorstOfWhateverConsumersExistNowOnOneSeriesPerStream() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        // Worst first, so a gauge that simply keeps the last consumer it saw reads 99 and fails here.
        give(1_000, consumer(DURABLE_BASE + "-scope-2-github", 800), consumer(CONSUMER, 900));

        monitor.poll();

        assertThat(gauge())
                .as("the worst of the durables present, not one series each")
                .isEqualTo(199d);

        // Scope 2 is gone and scope 3 is new. A tag per consumer would leave scope 2's 199 standing
        // on a series of its own forever; one series per stream has to follow the set that exists now.
        give(1_100, consumer(DURABLE_BASE + "-scope-3-github", 1_050));

        monitor.poll();

        assertThat(gauge()).isEqualTo(49d);
        assertThat(registry.find("webhook.stream.unacknowledged.gap").gauges())
                .hasSize(WebhookJetStreamBootstrap.STREAMS.length);
        assertThat(registry.find("webhook.stream.unacknowledged.deletions").counters())
                .hasSize(WebhookJetStreamBootstrap.STREAMS.length)
                .allSatisfy(counter ->
                        assertThat(counter.getId().getTag("consumer")).isNull());
    }

    @Test
    void publishesTheAgeOfTheOldestStoredMessageAsEffectiveRetention() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        oldestMessageAt(ZonedDateTime.now().minusDays(9));

        monitor.poll();

        assertThat(registry.get("webhook.stream.oldest.message.age")
                        .tag("stream", STREAM)
                        .gauge()
                        .value())
                .as("max-age is a ceiling and max-bytes a floor; this is the retention the deployment gets")
                .isCloseTo(Duration.ofDays(9).toSeconds(), within(60d));
    }

    @Test
    void saysSoWhenLossAccountingItselfStopsWorking(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, consumer(CONSUMER, 999));
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getStreamInfo(STREAM);

        monitor.poll();

        assertThat(counter())
                .as("a broker blip must not kill the scheduled task")
                .isZero();
        assertThat(output.getAll())
                .as("a frozen counter reads exactly like no loss, so the failure has to say so itself")
                .contains("the dropped-webhook counter is frozen, not zero");
    }

    @Test
    void reportsRecoveryAndOnlyTheFirstOfARunOfFailures(CapturedOutput output) throws Exception {
        WebhookStreamMonitor monitor = monitor();
        give(1_000, consumer(CONSUMER, 999));
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getStreamInfo(STREAM);
        monitor.poll();
        monitor.poll();
        give(1_000, consumer(CONSUMER, 999));

        monitor.poll();

        assertThat(output.getAll().split("counter is frozen", -1))
                .as("one line per outage, not one per poll")
                .hasSize(2);
        assertThat(output.getAll()).contains("Webhook loss accounting resumed for stream github");
    }

    @Test
    void reportsHowStaleTheLossCounterIs() throws Exception {
        WebhookStreamMonitor monitor = monitor();
        assertThat(pollAge())
                .as("never polled is not the same as polled and found nothing")
                .isNaN();

        give(1_000, 999);
        doThrow(new java.io.IOException("broker unreachable")).when(jsm).getStreamInfo(STREAM);
        monitor.poll();
        assertThat(pollAge())
                .as("a poll that failed did not maintain the counter, so it must not say it did")
                .isNaN();

        give(1_000, 999);
        monitor.poll();

        assertThat(pollAge()).isLessThan(5d);
    }

    @Test
    void waitsForTheInFlightPollToStopWithoutReportingAShutdownAsAnOutage(CapturedOutput output) throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException shutdown) {
                        interrupted.countDown();
                        // Simulate a broker call that must finish cleanup after cancellation.
                        release.await();
                        Thread.currentThread().interrupt();
                    }
                    throw new java.io.IOException("broker call cancelled during shutdown");
                })
                .when(jsm)
                .getStreamInfo(anyString());
        WebhookStreamMonitor monitor = monitor();
        try (var executor = Executors.newSingleThreadExecutor()) {
            monitor.start();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var stopping = executor.submit(() -> {
                    monitor.stop();
                    stopped.countDown();
                });
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(stopped.await(100, TimeUnit.MILLISECONDS))
                        .as("shutdown waits for the running poll, not only its cancellation request")
                        .isFalse();
                release.countDown();
                stopping.get(5, TimeUnit.SECONDS);
                assertThat(output.getAll()).doesNotContain("Webhook loss accounting stopped");
            } finally {
                release.countDown();
                monitor.stop();
            }
        }
    }

    private WebhookStreamMonitor monitor() {
        return new WebhookStreamMonitor(jsm, properties, DURABLE_BASE, registry);
    }

    /** Puts the stream at {@code firstSequence} with one consumer whose ack floor is {@code ackFloor}. */
    private void give(long firstSequence, long ackFloor) throws Exception {
        give(firstSequence, consumer(CONSUMER, ackFloor));
    }

    /** Puts the stream at a steady state whose oldest stored message dates from {@code first}. */
    private void oldestMessageAt(ZonedDateTime first) throws Exception {
        give(1_000, first, consumer(CONSUMER, 999));
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

    private static ConsumerInfo consumer(String name, long ackFloor) {
        SequenceInfo floor = mock(SequenceInfo.class);
        lenient().when(floor.getStreamSequence()).thenReturn(ackFloor);
        ConsumerInfo consumer = mock(ConsumerInfo.class);
        lenient().when(consumer.getName()).thenReturn(name);
        lenient().when(consumer.getAckFloor()).thenReturn(floor);
        return consumer;
    }

    private double counter() {
        return registry.get("webhook.stream.unacknowledged.deletions")
                .tag("stream", STREAM)
                .counter()
                .count();
    }

    private double pollAge() {
        return registry.get("webhook.stream.poll.age")
                .tag("stream", STREAM)
                .gauge()
                .value();
    }

    private double gauge() {
        return registry.get("webhook.stream.unacknowledged.gap")
                .tag("stream", STREAM)
                .gauge()
                .value();
    }
}
