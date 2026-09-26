package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.ConsumerContext;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.MessageHandler;
import io.nats.client.StreamContext;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ScopeConsumer}: lifecycle (start / stop / updateSubjects),
 * sequential dispatch invariants, and graceful drain.
 *
 * <p>Uses Mockito for the NATS client surface — no live JetStream connection. The dispatch
 * loop runs on a virtual thread spawned by {@link ScopeConsumer#start()}; assertions on
 * delivery order use a {@link CountDownLatch} to wait for that loop without busy-spinning.
 */
@Tag("unit")
class ScopeConsumerTest {

    private static final String STREAM = "github";
    private static final String CONSUMER_NAME = "hephaestus-scope-7";
    private static final long SCOPE_ID = 7L;
    private static final String[] SUBJECTS = new String[] {"github.acme.repo.>"};

    @Nested
    class ConstructorArgs {

        @Test
        void rejectsNullStreamName() {
            assertThatThrownBy(() -> new ScopeConsumer(
                            SCOPE_ID,
                            CONSUMER_NAME,
                            null,
                            mock(ConsumerContext.class),
                            mock(StreamContext.class),
                            SUBJECTS,
                            msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("streamName");
        }

        @Test
        void rejectsBlankConsumerName() {
            assertThatThrownBy(() -> new ScopeConsumer(
                            SCOPE_ID,
                            "  ",
                            STREAM,
                            mock(ConsumerContext.class),
                            mock(StreamContext.class),
                            SUBJECTS,
                            msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullMessageHandler() {
            assertThatThrownBy(() -> new ScopeConsumer(
                            SCOPE_ID,
                            CONSUMER_NAME,
                            STREAM,
                            mock(ConsumerContext.class),
                            mock(StreamContext.class),
                            SUBJECTS,
                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("messageHandler");
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void doubleStartIsNoOp() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(any(MessageHandler.class))).thenReturn(subscription);

            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, msg -> {});
            consumer.start();
            consumer.start();

            // consume() called exactly once even after two start() invocations.
            verify(ctx, times(1)).consume(any(MessageHandler.class));
            assertThat(consumer.isRunning()).isTrue();
            consumer.stop();
            assertThat(consumer.isRunning()).isFalse();
        }

        @Test
        void failedFirstAttachStopsDispatchSoStartCanBeRetried() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            List<Message> handled = new CopyOnWriteArrayList<>();
            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, handled::add);
            AtomicReference<@Nullable Thread> dispatch = new AtomicReference<>();
            when(ctx.consume(any(MessageHandler.class)))
                    .thenAnswer(invocation -> {
                        dispatch.set((Thread) ReflectionTestUtils.getField(consumer, "processorThread"));
                        throw new IOException("first attach failed");
                    })
                    .thenReturn(subscription);

            assertThatThrownBy(consumer::start).hasMessage("first attach failed");

            assertThat(Objects.requireNonNull(dispatch.get()).isAlive()).isFalse();
            assertThat(consumer.isRunning()).isFalse();

            try {
                consumer.start();
                assertThat(consumer.isAttached()).isTrue();
                ArgumentCaptor<MessageHandler> attached = ArgumentCaptor.forClass(MessageHandler.class);
                verify(ctx, times(2)).consume(attached.capture());
                Message message = mock(Message.class);
                attached.getValue().onMessage(message);
                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(handled).containsExactly(message));
            } finally {
                consumer.stop();
            }
        }

        @Test
        void doubleStopIsNoOp() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(any(MessageHandler.class))).thenReturn(subscription);

            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, msg -> {});
            consumer.start();
            consumer.stop();
            consumer.stop();
            // The subscription is closed exactly once across both stop() calls.
            verify(subscription, times(1)).close();
        }
    }

    @Nested
    class Dispatch {

        @Test
        @DisplayName("messages are handed to the handler in arrival order")
        void sequentialDispatch() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            ArgumentCaptor<MessageHandler> handlerCaptor = ArgumentCaptor.forClass(MessageHandler.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(handlerCaptor.capture())).thenReturn(subscription);

            CountDownLatch done = new CountDownLatch(3);
            AtomicInteger order = new AtomicInteger();
            int[] observedOrder = new int[3];

            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, msg -> {
                        int idx = order.getAndIncrement();
                        observedOrder[idx] = Integer.parseInt(
                                msg.getSubject().substring(msg.getSubject().lastIndexOf('.') + 1));
                        done.countDown();
                    });
            consumer.start();

            // Simulate three messages arriving in order via the captured callback.
            MessageHandler callback = handlerCaptor.getValue();
            callback.onMessage(messageWithSubject("github.acme.repo.1"));
            callback.onMessage(messageWithSubject("github.acme.repo.2"));
            callback.onMessage(messageWithSubject("github.acme.repo.3"));

            assertThat(done.await(2, TimeUnit.SECONDS))
                    .as("dispatch loop drained the queue in time")
                    .isTrue();
            assertThat(observedOrder).containsExactly(1, 2, 3);

            consumer.stop();
        }

        @Test
        void handlerExceptionDoesNotKillLoop() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            ArgumentCaptor<MessageHandler> handlerCaptor = ArgumentCaptor.forClass(MessageHandler.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(handlerCaptor.capture())).thenReturn(subscription);

            CountDownLatch survivedFailure = new CountDownLatch(1);
            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, msg -> {
                        if (msg.getSubject().endsWith(".boom")) {
                            throw new RuntimeException("simulated handler failure");
                        }
                        survivedFailure.countDown();
                    });
            consumer.start();

            MessageHandler callback = handlerCaptor.getValue();
            callback.onMessage(messageWithSubject("github.acme.repo.boom"));
            callback.onMessage(messageWithSubject("github.acme.repo.ok"));

            assertThat(survivedFailure.await(2, TimeUnit.SECONDS))
                    .as("loop survived the first handler failure and delivered the next message")
                    .isTrue();

            consumer.stop();
        }

        @Test
        void messageAfterStopIsNakd() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            ArgumentCaptor<MessageHandler> handlerCaptor = ArgumentCaptor.forClass(MessageHandler.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(handlerCaptor.capture())).thenReturn(subscription);

            AtomicInteger handlerInvocations = new AtomicInteger();
            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID,
                    CONSUMER_NAME,
                    STREAM,
                    ctx,
                    mock(StreamContext.class),
                    SUBJECTS,
                    msg -> handlerInvocations.incrementAndGet());
            consumer.start();
            consumer.stop();

            Message lateMessage = messageWithSubject("github.acme.repo.late");
            handlerCaptor.getValue().onMessage(lateMessage);

            verify(lateMessage, atLeastOnce()).nak();
            assertThat(handlerInvocations.get()).isZero();
        }

        @Test
        void stopWaitsForInFlightHandler() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            ArgumentCaptor<MessageHandler> handlerCaptor = ArgumentCaptor.forClass(MessageHandler.class);
            when(ctx.consume(handlerCaptor.capture())).thenReturn(mock(MessageConsumer.class));
            CountDownLatch handlerStarted = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            CountDownLatch stopped = new CountDownLatch(1);
            ScopeConsumer consumer = new ScopeConsumer(
                    SCOPE_ID, CONSUMER_NAME, STREAM, ctx, mock(StreamContext.class), SUBJECTS, msg -> {
                        handlerStarted.countDown();
                        while (releaseHandler.getCount() > 0) {
                            try {
                                releaseHandler.await();
                            } catch (InterruptedException ignored) {
                            }
                        }
                    });
            consumer.start();
            handlerCaptor.getValue().onMessage(messageWithSubject("github.acme.repo.in-flight"));
            assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            Thread.ofVirtual().start(() -> {
                consumer.stop();
                stopped.countDown();
            });

            assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse();
            releaseHandler.countDown();
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    class UpdateSubjects {

        @Test
        void identicalSubjectsAreNoOp() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            StreamContext streamCtx = mock(StreamContext.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(any(MessageHandler.class))).thenReturn(subscription);

            ScopeConsumer consumer =
                    new ScopeConsumer(SCOPE_ID, CONSUMER_NAME, STREAM, ctx, streamCtx, SUBJECTS, msg -> {});
            consumer.start();
            consumer.updateSubjects(SUBJECTS.clone());

            verify(streamCtx, never()).createOrUpdateConsumer(any());
            consumer.stop();
        }

        @Test
        void differentSubjectsReconfigure() throws Exception {
            ConsumerContext ctx = mock(ConsumerContext.class);
            ConsumerInfo info = mock(ConsumerInfo.class);
            ConsumerConfiguration cfg = ConsumerConfiguration.builder().build();
            when(ctx.getConsumerInfo()).thenReturn(info);
            when(info.getConsumerConfiguration()).thenReturn(cfg);

            StreamContext streamCtx = mock(StreamContext.class);
            MessageConsumer subscription = mock(MessageConsumer.class);
            when(ctx.consume(any(MessageHandler.class))).thenReturn(subscription);

            ScopeConsumer consumer =
                    new ScopeConsumer(SCOPE_ID, CONSUMER_NAME, STREAM, ctx, streamCtx, SUBJECTS, msg -> {});
            consumer.start();
            consumer.updateSubjects(new String[] {"github.acme.other.>"});

            verify(streamCtx, times(1)).createOrUpdateConsumer(any());
            // The old subscription was closed and a new one attached.
            verify(subscription, atLeastOnce()).close();
            verify(ctx, times(2)).consume(any(MessageHandler.class));
            assertThat(consumer.currentSubjects()).containsExactly("github.acme.other.>");

            consumer.stop();
        }
    }

    private static Message messageWithSubject(String subject) {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(subject);
        return msg;
    }
}
