package de.tum.cit.aet.hephaestus.core.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ProxyStreamingDeadlineTest extends BaseUnitTest {

    @Test
    @DisplayName("frequent frames do not restart the total exchange allowance")
    void shouldKeepTheOriginalLimitWhenFramesKeepArriving() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        // Keep the blocking servlet owner off the scheduler that virtual time replaces.
        var requestScheduler = Schedulers.fromExecutorService(executor);
        var subscribed = new CountDownLatch(1);
        var firstWrite = new CountDownLatch(1);
        var secondWrite = new CountDownLatch(1);
        var thirdWrite = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        var delegate = response.getOutputStream();
        ServletOutputStream output = mock(ServletOutputStream.class, call -> {
            if (call.getMethod().getName().equals("write")) {
                delegate.write(call.getArgument(0, byte[].class));
                switch (writes.incrementAndGet()) {
                    case 1 -> firstWrite.countDown();
                    case 2 -> secondWrite.countDown();
                    case 3 -> thirdWrite.countDown();
                    default -> {}
                }
            } else if (call.getMethod().getName().equals("flush")) {
                delegate.flush();
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        var servlet = new HttpServletResponseWrapper(response) {
            @Override
            public ServletOutputStream getOutputStream() {
                return output;
            }
        };
        try {
            StepVerifier.withVirtualTime(() -> Mono.fromCallable(() -> {
                                Flux<DataBuffer> frames = Flux.<DataBuffer>create(sink -> {
                                            sink.next(frame(0));
                                            var index = new AtomicInteger(1);
                                            var ticks = Schedulers.parallel()
                                                    .schedulePeriodically(
                                                            () -> sink.next(frame(index.getAndIncrement())),
                                                            2,
                                                            2,
                                                            TimeUnit.SECONDS);
                                            sink.onDispose(ticks);
                                            // Count down after the periodic task is armed, not just onSubscribe.
                                            subscribed.countDown();
                                        })
                                        .doOnCancel(() -> cancelled.set(true));
                                var upstream = ClientResponse.create(HttpStatus.OK)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                                        .body(frames)
                                        .build();
                                return ProxyStreamingUtils.forward(
                                        ProxyStreamingUtils.parts(upstream), Duration.ofSeconds(5), servlet, null);
                            })
                            .subscribeOn(requestScheduler))
                    .then(() -> {
                        await(subscribed);
                        await(firstWrite);
                    })
                    .thenAwait(Duration.ofSeconds(2))
                    .then(() -> await(secondWrite))
                    .thenAwait(Duration.ofSeconds(2))
                    .then(() -> await(thirdWrite))
                    .thenAwait(Duration.ofSeconds(2))
                    .assertNext(result -> {
                        assertThat(result.streamOutcome()).isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
                        assertThat(response.getStatus()).isEqualTo(200);
                        assertThat(response.isCommitted()).isTrue();
                        assertThat(response.getContentAsByteArray())
                                .isEqualTo("data: frame-0\n\ndata: frame-1\n\ndata: frame-2\n\n".getBytes(UTF_8));
                        assertThat(cancelled).isTrue();
                    })
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
        } finally {
            requestScheduler.dispose();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static DataBuffer frame(int index) {
        return DefaultDataBufferFactory.sharedInstance.wrap(("data: frame-" + index + "\n\n").getBytes(UTF_8));
    }
}
