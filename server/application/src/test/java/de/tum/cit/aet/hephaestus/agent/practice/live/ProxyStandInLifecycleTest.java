package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
// Every stand-in reads the request limit from the JVM's system properties when it is constructed.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProxyStandInLifecycleTest {
    private static final byte[] SSE = "data: {\"text\":\"hello\"}\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ERROR =
            "{\"error\":{\"message\":\"loopback failure\"}}".getBytes(StandardCharsets.UTF_8);
    private static final String REQUEST_LIMIT = "hephaestus.live.criteriaRequestLimit";
    private static final String SYNTHETIC_KEY = "loopback-only";
    /** One admissible result, as the runner posts it after measuring. */
    private static final String ADMISSION = """
            {"contractVersion":3,"reviewId":"10000000-0000-4000-8000-000000000001",\
            "jobId":"20000000-0000-4000-8000-000000000001","attempt":0,"nonResults":[],"results":[\
            {"practiceSlug":"handles-errors","itemId":"1","itemVersion":"fixture:1","readReceipts":[],\
            "candidate":{"outcome":"MET","summary":"Handles errors","explanation":"The handler records the failure.",\
            "citations":[],"evidence":{"openQuestions":[]},"coverage":{"source":"host","complete":true,"reads":0,\
            "truncatedReads":0,"failedReads":0,"receipts":[]}}}]}""";

    @Test
    void shouldTerminateForwardingWhenUpstreamWithholdsHeaders() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_HEADERS)) {
            var response = harness.request();
            harness.awaitAccepted();

            harness.closeProxy();

            assertThat(response.handle((value, failure) -> failure).get(3, TimeUnit.SECONDS))
                    .isNotNull();
            harness.assertListenerStopped();
            // Closing an already closed stand-in must also finish while the upstream remains held.
            harness.closeProxy();
        }
    }

    @Test
    void shouldTerminateForwardingWhenDownstreamWithholdsRequestBody() throws Exception {
        try (Harness harness = new Harness(Reply.FINITE_JSON);
                Socket socket = new Socket(
                        InetAddress.getLoopbackAddress(),
                        URI.create(harness.proxy.baseUrl()).getPort())) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream()
                    .write(("POST /v1/chat/completions HTTP/1.1\r\n"
                                    + "Host: localhost\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try (var headers =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                assertThat(headers.readLine()).contains(" 100 ");
                String header;
                while ((header = headers.readLine()) != null && !header.isEmpty()) {
                    // Consume the provisional response before withholding the second body byte.
                }
                socket.getOutputStream().write('{');
                socket.getOutputStream().flush();

                harness.closeProxy();

                boolean connectionClosed;
                try {
                    connectionClosed = socket.getInputStream().read() == -1;
                } catch (SocketException connectionReset) {
                    connectionClosed = true;
                }
                assertThat(connectionClosed).isTrue();
                assertThat(harness.accepted.getCount())
                        .as("an incomplete request is never forwarded")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void shouldTerminateBodyReaderWhenUpstreamStallsAfterAnSseFrame() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_SSE)) {
            var response = harness.request().get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
            try (InputStream body = response.body()) {
                // The frame arrives while the upstream still stalls: the stand-in streams the answer, not buffers it.
                assertThat(harness.tasks
                                .submit(() -> body.readNBytes(SSE.length))
                                .get(3, TimeUnit.SECONDS))
                        .isEqualTo(SSE);
                CountDownLatch reading = new CountDownLatch(1);
                Future<?> reader = harness.tasks.submit(() -> {
                    reading.countDown();
                    try {
                        body.readAllBytes();
                    } catch (IOException expectedOnCancellation) {
                        // Cancellation may close the response stream instead of returning EOF.
                    }
                });
                assertThat(reading.await(3, TimeUnit.SECONDS)).isTrue();

                harness.closeProxy();

                reader.get(3, TimeUnit.SECONDS);
                harness.assertListenerStopped();
            }
        }
    }

    @Test
    void shouldPreserveRunnerFailureWhenClosingStalledForwarding() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_HEADERS)) {
            var response = harness.request();
            harness.awaitAccepted();
            AssertionError runnerFailure = new AssertionError("runner did not finish within its budget");
            Future<AssertionError> failedRun = harness.tasks.submit(() -> {
                try (harness.proxy) {
                    throw runnerFailure;
                } catch (AssertionError failure) {
                    return failure;
                }
            });
            harness.closeTask = failedRun;

            assertThat(failedRun.get(7, TimeUnit.SECONDS)).isSameAs(runnerFailure);
            assertThat(runnerFailure.getSuppressed()).isEmpty();
            assertThat(response.handle((value, failure) -> failure).get(3, TimeUnit.SECONDS))
                    .isNotNull();
            harness.assertListenerStopped();
        }
    }

    @Test
    void shouldPreserveFiniteJsonResponseWhenForwardingCompletes() throws Exception {
        assertFiniteResponse(Reply.FINITE_JSON, "application/json", JSON);
    }

    @Test
    void shouldPreserveFiniteSseResponseWhenForwardingCompletes() throws Exception {
        assertFiniteResponse(Reply.FINITE_SSE, "text/event-stream", SSE);
    }

    @Test
    void shouldForwardAtMostTheLimitAndRefuseTheRestLocallyWhenRequestLimitIsSet() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_HEADERS, "2")) {
            var first = harness.request();
            var second = harness.request();
            harness.awaitReceipts(2);

            // Both admitted requests are still held upstream when the third arrives.
            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));
            assertThat(harness.receipts()).isEqualTo(2);

            harness.releaseUpstream();
            for (var admitted : List.of(first, second)) {
                var response = admitted.get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(harness.readBody(response)).isEqualTo(SSE);
            }
            // A finished request does not return its slot.
            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));
            assertThat(harness.receipts()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Reply.class,
            names = {"SERVER_ERROR", "DROPPED"})
    void shouldKeepTheSlotSpentWhenTheUpstreamRequestFails(Reply failure) throws Exception {
        try (Harness harness = new Harness(failure, "1")) {
            var attempt = harness.request();
            harness.awaitReceipts(1);
            var settled = attempt.handle((response, error) -> response).get(3, TimeUnit.SECONDS);
            if (failure == Reply.SERVER_ERROR) {
                assertThat(settled).isNotNull();
                assertThat(settled.statusCode()).isEqualTo(500);
                assertThat(harness.readBody(settled)).isEqualTo(ERROR);
            } else {
                assertThat(settled)
                        .as("a dropped upstream connection fails the request")
                        .isNull();
            }

            // The retry a client makes after a failure is a new request for the same, already spent, slot.
            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));
            assertThat(harness.receipts()).isEqualTo(1);
        }
    }

    @Test
    void shouldForwardEveryRequestWhenNoRequestLimitIsSet() throws Exception {
        try (Harness harness = new Harness(Reply.FINITE_JSON)) {
            for (int call = 0; call < 3; call++) {
                var response = harness.request().get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(harness.readBody(response)).isEqualTo(JSON);
            }
            assertThat(harness.receipts()).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "twelve", "0", "-1", "2147483648"})
    void shouldRefuseToStartWhenRequestLimitIsInvalid(String limit) {
        String previous = System.getProperty(REQUEST_LIMIT);
        System.setProperty(REQUEST_LIMIT, limit);
        try {
            // A stand-in that accepts the value is closed at once, so the failed assertion leaves nothing running.
            assertThatThrownBy(() -> new PracticeRunnerLiveLlmTest.ProxyStandIn(
                                    new LiveLlmCredentials("http://127.0.0.1:9", SYNTHETIC_KEY, "unused"))
                            .close())
                    .hasMessageContaining(REQUEST_LIMIT);
        } finally {
            restoreRequestLimit(previous);
        }
    }

    @Test
    void shouldSendExactlyTheLimitWhenMoreRequestsArriveTogether() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_HEADERS, "2")) {
            List<CompletableFuture<HttpResponse<InputStream>>> calls =
                    List.of(harness.request(), harness.request(), harness.request());

            // The two requests that take the slots stay held upstream, so the only answer that can arrive now is
            // the refusal of the third, whichever of the three it is.
            CompletableFuture.anyOf(calls.toArray(CompletableFuture<?>[]::new)).get(3, TimeUnit.SECONDS);
            harness.awaitReceipts(2);
            var answered = calls.stream().filter(CompletableFuture::isDone).toList();
            assertThat(answered).hasSize(1);
            harness.assertLocallyDenied(answered.getFirst().get());

            var forwarded = calls.stream().filter(call -> !call.isDone()).toList();
            assertThat(forwarded).hasSize(2);
            harness.releaseUpstream();
            for (var call : forwarded) {
                var response = call.get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(harness.readBody(response)).isEqualTo(SSE);
            }
            assertThat(harness.receipts()).isEqualTo(2);
        }
    }

    @Test
    void shouldKeepTheSlotSpentWhenTheCallerCancelsItsRequest() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_HEADERS, "1")) {
            var cancelled = harness.request();
            harness.awaitReceipts(1);
            assertThat(cancelled.cancel(true)).isTrue();

            // The cancelled request was already sent upstream, so its slot stays spent while it is held there.
            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));

            // It also stays spent once the upstream has answered the request that nobody now reads.
            harness.releaseUpstream();
            harness.awaitAnswered(1);
            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));
            assertThat(harness.receipts()).isEqualTo(1);
        }
    }

    @Test
    void shouldFailTheMeasurementWhenARequestIsRefusedAfterValidAnswers() throws Exception {
        try (Harness harness = new Harness(Reply.FINITE_JSON, "1")) {
            // Result admission is answered here and spends no model request.
            var admission = harness.admitResults().get(3, TimeUnit.SECONDS);
            assertThat(admission.statusCode()).isEqualTo(200);
            harness.readBody(admission);

            var answer = harness.request().get(3, TimeUnit.SECONDS);
            assertThat(answer.statusCode()).isEqualTo(201);
            assertThat(harness.readBody(answer)).isEqualTo(JSON);
            harness.proxy.assertRequestLimitNotSpent();

            harness.assertLocallyDenied(harness.request().get(3, TimeUnit.SECONDS));
            // The earlier valid answer does not hide the refusal.
            assertThatThrownBy(harness.proxy::assertRequestLimitNotSpent).isInstanceOf(AssertionError.class);
            assertThat(harness.receipts()).isEqualTo(1);
        }
    }

    private static void restoreRequestLimit(@Nullable String previous) {
        if (previous == null) {
            System.clearProperty(REQUEST_LIMIT);
        } else {
            System.setProperty(REQUEST_LIMIT, previous);
        }
    }

    private static void assertFiniteResponse(Reply reply, String contentType, byte[] expected) throws Exception {
        try (Harness harness = new Harness(reply)) {
            var response = harness.request().get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("content-type")).contains(contentType);
            try (var body = response.body()) {
                assertThat(harness.tasks.submit(body::readAllBytes).get(3, TimeUnit.SECONDS))
                        .isEqualTo(expected);
            }
            harness.closeProxy();
            harness.assertListenerStopped();
        }
    }

    private enum Reply {
        STALLED_HEADERS,
        STALLED_SSE,
        FINITE_JSON,
        FINITE_SSE,
        SERVER_ERROR,
        DROPPED
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService upstreamTasks = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient client = HttpClient.newHttpClient();
        private final HttpServer upstream;
        private final PracticeRunnerLiveLlmTest.ProxyStandIn proxy;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger receipts = new AtomicInteger();
        private final Semaphore received = new Semaphore(0);
        private final Semaphore answered = new Semaphore(0);
        private @Nullable Future<?> closeTask;

        Harness(Reply reply) throws IOException {
            this(reply, null);
        }

        /** {@code requestLimit} is the stand-in's request limit property, or null for none. */
        Harness(Reply reply, @Nullable String requestLimit) throws IOException {
            upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            upstream.setExecutor(upstreamTasks);
            upstream.createContext("/", exchange -> reply(exchange, reply));
            upstream.start();
            String previous = System.getProperty(REQUEST_LIMIT);
            try {
                if (requestLimit == null) {
                    System.clearProperty(REQUEST_LIMIT);
                } else {
                    System.setProperty(REQUEST_LIMIT, requestLimit);
                }
                proxy = new PracticeRunnerLiveLlmTest.ProxyStandIn(new LiveLlmCredentials(
                        "http://127.0.0.1:" + upstream.getAddress().getPort(), SYNTHETIC_KEY, "unused"));
            } finally {
                restoreRequestLimit(previous);
            }
        }

        CompletableFuture<HttpResponse<InputStream>> request() {
            return client.sendAsync(
                    HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/v1/chat/completions"))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }

        CompletableFuture<HttpResponse<InputStream>> admitResults() {
            return client.sendAsync(
                    HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/admit-results"))
                            .POST(HttpRequest.BodyPublishers.ofString(ADMISSION))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }

        void awaitAccepted() throws InterruptedException {
            assertThat(accepted.await(3, TimeUnit.SECONDS)).isTrue();
        }

        /** Waits until the upstream has finished answering {@code count} more requests. */
        void awaitAnswered(int count) throws InterruptedException {
            assertThat(answered.tryAcquire(count, 3, TimeUnit.SECONDS)).isTrue();
        }

        /** Waits until the upstream has read {@code count} more complete requests. */
        void awaitReceipts(int count) throws InterruptedException {
            assertThat(received.tryAcquire(count, 3, TimeUnit.SECONDS)).isTrue();
        }

        int receipts() {
            return receipts.get();
        }

        void releaseUpstream() {
            release.countDown();
        }

        byte[] readBody(HttpResponse<InputStream> response) throws Exception {
            try (InputStream body = response.body()) {
                return tasks.submit(body::readAllBytes).get(3, TimeUnit.SECONDS);
            }
        }

        void assertLocallyDenied(HttpResponse<InputStream> response) throws Exception {
            assertThat(response.statusCode())
                    .as("a request beyond the limit is refused with a status a client does not retry")
                    .isBetween(400, 499)
                    .isNotIn(408, 409, 429);
            assertThat(new String(readBody(response), StandardCharsets.UTF_8))
                    .as("the refusal carries no credential")
                    .doesNotContain(SYNTHETIC_KEY);
        }

        void closeProxy() throws InterruptedException, ExecutionException, TimeoutException {
            closeTask = tasks.submit(proxy::close);
            closeTask.get(7, TimeUnit.SECONDS);
            assertThat(release.getCount())
                    .as("upstream remains stalled during cancellation")
                    .isEqualTo(1);
        }

        void assertListenerStopped() {
            assertThatThrownBy(() -> client.send(
                            HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/after-close"))
                                    .timeout(Duration.ofSeconds(1))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(IOException.class);
        }

        private void reply(HttpExchange exchange, Reply reply) throws IOException {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                receipts.incrementAndGet();
                accepted.countDown();
                received.release();
                if (reply == Reply.DROPPED) {
                    // Closing an exchange that sent no response headers closes its connection.
                    return;
                }
                if (reply == Reply.SERVER_ERROR) {
                    exchange.getResponseHeaders().set("content-type", "application/json");
                    exchange.sendResponseHeaders(500, ERROR.length);
                    exchange.getResponseBody().write(ERROR);
                    return;
                }
                if (reply == Reply.STALLED_HEADERS) {
                    awaitRelease();
                }
                boolean sse = reply != Reply.FINITE_JSON;
                exchange.getResponseHeaders().set("content-type", sse ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(201, 0);
                exchange.getResponseBody().write(sse ? SSE : JSON);
                exchange.getResponseBody().flush();
                if (reply == Reply.STALLED_SSE) {
                    awaitRelease();
                }
            } finally {
                answered.release();
            }
        }

        private void awaitRelease() throws IOException {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("Loopback upstream was not released by test cleanup");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Loopback upstream interrupted", failure);
            }
        }

        @Override
        public void close() {
            // Release only after the measured close. This also lets the old orderly close finish after a failed
            // assertion.
            release.countDown();
            upstream.stop(0);
            client.shutdownNow();
            Future<?> pendingClose = closeTask;
            if (pendingClose == null) {
                pendingClose = tasks.submit(proxy::close);
            }
            List<Exception> failures = new ArrayList<>();
            try {
                pendingClose.get(7, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                failures.add(failure);
            } catch (ExecutionException | TimeoutException failure) {
                failures.add(failure);
            }
            tasks.shutdownNow();
            upstreamTasks.shutdownNow();
            try {
                if (!tasks.awaitTermination(3, TimeUnit.SECONDS)) {
                    failures.add(new IllegalStateException("Loopback task executor did not stop"));
                }
                if (!upstreamTasks.awaitTermination(3, TimeUnit.SECONDS)) {
                    failures.add(new IllegalStateException("Loopback upstream executor did not stop"));
                }
                if (!client.awaitTermination(Duration.ofSeconds(3))) {
                    failures.add(new IllegalStateException("Loopback client did not stop"));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                failures.add(failure);
            }
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("Loopback cleanup did not finish");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }
}
