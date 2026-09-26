package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChannel;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * Pins the invariants that make the Slack stream feel live AND stay robust: the reply streams in more than
 * one write (time-based coalescing, not one terminal dump), the full text is delivered exactly once in order,
 * a transient Slack failure is retried without aborting the turn, and only a genuine "gone" error disconnects.
 */
class SlackStreamingMentorChannelTest extends BaseUnitTest {

    private static final long WS = 1L;
    private static final String CH = "D123";
    private static final String THREAD = "1700000000.000100";

    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger appends = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();

    private SlackMessageService slackThatStreamsOk() {
        SlackMessageService slack = mock(SlackMessageService.class);
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    delivered.add(inv.getArgument(3));
                    starts.incrementAndGet();
                    return "1700000000.999999";
                });
        lenient()
                .doAnswer(inv -> {
                    delivered.add(inv.getArgument(3));
                    appends.incrementAndGet();
                    return null;
                })
                .when(slack)
                .appendStream(anyLong(), anyString(), anyString(), anyString());
        doAnswer(inv -> {
                    stops.incrementAndGet();
                    return null;
                })
                .when(slack)
                .stopStream(anyLong(), anyString(), anyString(), any());
        return slack;
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static UIMessageChunk.TextDelta delta(String s) {
        return new UIMessageChunk.TextDelta("m1", s);
    }

    @Test
    @DisplayName("streams incrementally (>1 write) and delivers the full text once, in order")
    void streamsIncrementallyAndPreservesContent() throws InterruptedException {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(
                slack, WS, CH, THREAD, Duration.ofSeconds(2), Duration.ofSeconds(10), 5, 10);

        String[] words = {"The ", "token ", "validator ", "ships ", "without ", "any ", "unit ", "tests. "};
        for (int i = 0; i < 4; i++) {
            channel.send(delta(words[i]));
        }
        waitUntil(() -> starts.get() == 1, 1000);
        for (int i = 4; i < words.length; i++) {
            channel.send(delta(words[i]));
        }
        waitUntil(() -> appends.get() >= 1, 1000);
        channel.completeWithDone();
        waitUntil(() -> stops.get() >= 1, 4000);

        assertThat(starts.get()).isEqualTo(1);
        assertThat(appends.get())
                .as("reply must stream in more than the initial open")
                .isGreaterThanOrEqualTo(1);
        assertThat(stops.get()).isEqualTo(1);
        assertThat(String.join("", delivered)).isEqualTo(String.join("", words));
        verify(slack).startStream(eq(WS), eq(CH), eq(THREAD), anyString());
    }

    @Test
    @DisplayName("successful turns do not attach bulky feedback buttons")
    void successfulTurnDoesNotAttachFeedbackButtons() {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("Try keeping review comments specific. "));
        channel.completeWithDone();
        waitUntil(() -> stops.get() >= 1, 4000);

        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.captor();
        verify(slack).stopStream(eq(WS), eq(CH), anyString(), blocks.capture());
        assertThat(blocks.getValue())
                .as("mentor answers should not add a bulky feedback row")
                .isEmpty();
    }

    @Test
    @DisplayName("collapses repeated model answer candidates before they reach Slack")
    void repeatedModelAnswerCandidatesAreCollapsed() {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(
                delta("Hey! Which of the recent items - PR #12, PR #9, or issue #15 - do you want to look at next?"));
        channel.send(delta("Hey there! Which of the recent items - PR #12, PR #9, or issue #15 - should we focus on?"));

        channel.completeWithDone();
        waitUntil(() -> stops.get() >= 1, 4000);

        assertThat(starts.get()).isEqualTo(1);
        assertThat(stops.get()).isEqualTo(1);
        assertThat(String.join("", delivered))
                .isEqualTo(
                        "Hey! Which of the recent items - PR #12, PR #9, or issue #15 - do you want to look at next?");
    }

    @Test
    @DisplayName("normalizes non-ASCII punctuation before text reaches Slack")
    void normalizesNonAsciiPunctuation() {
        assertThat(SlackMentorTextFilter.normalize("Slack\u2011mentor — “review”…"))
                .isEqualTo("Slack-mentor - \"review\"...");
    }

    @Test
    @DisplayName("drops leaked internal analysis before streaming mentor text to Slack")
    void leakedInternalAnalysisIsNotStreamed() {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("User wants to see Slack messages in context. "
                + "We need to fetch inputs/context/slack_conversations.json. "
                + "I can use your recent Slack threads as context. Which thread should we look at?"));
        channel.completeWithDone();
        waitUntil(() -> stops.get() >= 1, 4000);

        assertThat(String.join("", delivered))
                .isEqualTo("I can use your recent Slack threads as context. Which thread should we look at?");
    }

    @Test
    @DisplayName("keeps distinct adjacent guidance even when some words overlap")
    void distinctAdjacentGuidanceIsNotCollapsed() {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("Your PR validation is stronger. "));
        channel.send(delta("Your issue triage still needs tests."));
        channel.completeWithDone();
        waitUntil(() -> stops.get() >= 1, 4000);

        assertThat(String.join("", delivered))
                .isEqualTo("Your PR validation is stronger. Your issue triage still needs tests.");
    }

    @Test
    @DisplayName("failed turns do not attach helpful/not helpful feedback buttons")
    void failedTurnDoesNotAttachFeedbackButtons() {
        SlackMessageService slack = slackThatStreamsOk();
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.completeWithError("Hephaestus is not ready to mentor in this workspace yet.");
        waitUntil(() -> stops.get() >= 1, 4000);

        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.captor();
        verify(slack).stopStream(eq(WS), eq(CH), anyString(), blocks.capture());
        assertThat(blocks.getValue())
                .as("failed mentor turns should not ask for quality feedback")
                .isEmpty();
    }

    @Test
    @DisplayName("in-flight conflicts update assistant status without posting another message")
    void conflictUpdatesStatusWithoutPostingMessage() {
        SlackMessageService slack = mock(SlackMessageService.class);
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.completeWithConflict();

        verify(slack).setStatus(eq(WS), eq(CH), eq(THREAD), eq("Still working on the previous message..."));
        verify(slack, never()).startStream(anyLong(), anyString(), anyString(), anyString());
        verify(slack, never()).stopStream(anyLong(), anyString(), anyString(), any());
    }

    private static Stream<Arguments> transientStartStreamFailures() {
        return Stream.of(
                Arguments.of("bare rate-limit error", new SlackSendException(WS, CH, "ratelimited")),
                Arguments.of("429 with Retry-After", new SlackSendException(WS, CH, "ratelimited", 20L)));
    }

    @ParameterizedTest(name = "{0} is retried and the turn is NOT aborted")
    @MethodSource("transientStartStreamFailures")
    void retriesWithoutAbort_onTransientStartStreamFailure(String description, SlackSendException failure) {
        SlackMessageService slack = mock(SlackMessageService.class);
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger startCalls = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    // First open fails transiently; the loop must re-buffer and retry, not give up.
                    if (startCalls.incrementAndGet() == 1) {
                        throw failure;
                    }
                    got.add(inv.getArgument(3));
                    return "ts";
                });
        // The fast path finalizes via startStream; appendStream may or may not be hit depending on timing.
        lenient()
                .doAnswer(inv -> {
                    got.add(inv.getArgument(3));
                    return null;
                })
                .when(slack)
                .appendStream(anyLong(), anyString(), anyString(), anyString());
        doAnswer(inv -> {
                    stopped.set(true);
                    return null;
                })
                .when(slack)
                .stopStream(anyLong(), anyString(), anyString(), any());

        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);
        AtomicBoolean disconnected = new AtomicBoolean();
        channel.onDisconnect(() -> disconnected.set(true));

        channel.send(delta("hello world "));
        channel.completeWithDone();
        waitUntil(stopped::get, 5000);

        assertThat(disconnected.get())
                .as("a transient failure must not be treated as a disconnect")
                .isFalse();
        assertThat(startCalls.get())
                .as("startStream retried after the transient failure")
                .isGreaterThanOrEqualTo(2);
        assertThat(String.join("", got)).contains("hello world");
    }

    @Test
    @DisplayName("a slow flush tick still in startStream at finish never opens the stream twice")
    void slowTickAtFinishNeverDoubleOpensStream() throws InterruptedException {
        SlackMessageService slack = mock(SlackMessageService.class);
        AtomicInteger startCalls = new AtomicInteger();
        CountDownLatch startEntered = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        // The first startStream blocks until released, IGNORING interrupts — modelling an OkHttp call that does not
        // respond to shutdownNow()'s interrupt. This keeps a flush tick "in-flight" while finish() runs.
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    startCalls.incrementAndGet();
                    startEntered.countDown();
                    while (!released.get()) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            // swallow: OkHttp does not abort on interrupt
                        }
                    }
                    return "ts-" + startCalls.get();
                });
        lenient().doAnswer(inv -> null).when(slack).appendStream(anyLong(), anyString(), anyString(), anyString());
        lenient().doAnswer(inv -> null).when(slack).stopStream(anyLong(), anyString(), anyString(), any());

        var channel = new SlackStreamingMentorChannel(
                slack, WS, CH, THREAD, Duration.ofMillis(20), Duration.ofSeconds(2), 350, 1000);
        channel.send(delta("first words here "));
        assertThat(startEntered.await(3, TimeUnit.SECONDS))
                .as("flush tick should open the stream")
                .isTrue();

        Thread finisher = new Thread(channel::completeWithDone, "finisher");
        finisher.start();
        Thread.sleep(50);
        released.set(true);
        finisher.join(8000);

        assertThat(startCalls.get())
                .as("the stream must be opened exactly once, never twice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a genuine 'gone' error fires the disconnect hook once")
    void goneErrorDisconnects() {
        SlackMessageService slack = mock(SlackMessageService.class);
        when(slack.startStream(anyLong(), eq(CH), anyString(), anyString()))
                .thenThrow(new SlackSendException(WS, CH, "channel_not_found"));

        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);
        AtomicInteger disconnects = new AtomicInteger();
        channel.onDisconnect(disconnects::incrementAndGet);

        channel.send(delta("anything here "));
        waitUntil(() -> disconnects.get() >= 1, 4000);
        assertThat(channel.isClientGone()).isTrue();

        channel.completeWithDone(); // terminal must not fire the hook again
        assertThat(disconnects.get()).isEqualTo(1);
    }

    @Test
    void noWriteBlockedBySilentModeIsSuppressed() {
        SlackMessageService slack = mock(SlackMessageService.class);
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new SlackSendException(WS, CH, "silent_mode_engaged"));
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("suppressed response"));
        assertThat(channel.completeWithDone()).isEqualTo(MentorChannel.DeliveryOutcome.INSTANCE_SILENCED);
    }

    @Test
    void silentModeMidStreamSuppressesTheReply() {
        SlackMessageService slack = mock(SlackMessageService.class);
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("ts");
        doThrow(new SlackSendException(WS, CH, "silent_mode_engaged"))
                .when(slack)
                .appendStream(anyLong(), anyString(), anyString(), anyString());
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("first "));
        verify(slack, timeout(4000)).startStream(WS, CH, THREAD, "first ");
        channel.send(delta("second "));
        waitUntil(channel::isClientGone, 4000);

        assertThat(channel.isClientGone()).isTrue();
        assertThat(channel.completeWithDone()).isEqualTo(MentorChannel.DeliveryOutcome.INSTANCE_SILENCED);
    }

    @Test
    void replyWhoseTailNeverReachedSlackIsNotDelivered() {
        SlackMessageService slack = mock(SlackMessageService.class);
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("ts");
        doThrow(new SlackSendException(WS, CH, "internal_error"))
                .when(slack)
                .appendStream(anyLong(), anyString(), anyString(), anyString());
        var channel = new SlackStreamingMentorChannel(slack, WS, CH, THREAD);

        channel.send(delta("first "));
        verify(slack, timeout(4000)).startStream(WS, CH, THREAD, "first ");
        channel.send(delta("tail"));

        assertThat(channel.completeWithDone()).isEqualTo(MentorChannel.DeliveryOutcome.NOT_DELIVERED);
    }

    @Test
    void replyIsNotDeliveredWhenAFlushOutlivesTheFinishAndFails() throws Exception {
        SlackMessageService slack = mock(SlackMessageService.class);
        CountDownLatch flushing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        when(slack.startStream(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    if (opens.incrementAndGet() > 1) {
                        return "ts";
                    }
                    flushing.countDown();
                    // Like an OkHttp call, the write ignores the interrupt that stops the flusher.
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException ignored) {
                            // keep waiting
                        }
                    }
                    throw new SlackSendException(WS, CH, "internal_error");
                });
        var channel = new SlackStreamingMentorChannel(
                slack, WS, CH, THREAD, Duration.ofMillis(50), Duration.ofMillis(50), 0, 1000);
        channel.send(delta("first "));
        assertThat(flushing.await(4, TimeUnit.SECONDS)).isTrue();
        channel.send(delta("tail"));

        var outcome = new java.util.concurrent.CompletableFuture<MentorChannel.DeliveryOutcome>();
        Thread finisher = Thread.ofVirtual().start(() -> outcome.complete(channel.completeWithDone()));
        // The terminal write is queued behind the stuck flush once both shutdown waits have elapsed.
        waitUntil(() -> finisher.getState() == Thread.State.WAITING, 4000);
        release.countDown();

        assertThat(outcome.get(4, TimeUnit.SECONDS)).isEqualTo(MentorChannel.DeliveryOutcome.NOT_DELIVERED);
    }
}
