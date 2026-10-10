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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProxyStandInLifecycleTest {
    private static final byte[] SSE = "data: {\"text\":\"hello\"}\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

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
            var headers = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(headers.readLine()).contains(" 100 ");
            String header;
            while ((header = headers.readLine()) != null && !header.isEmpty()) {
                // Consume the provisional response before withholding the second body byte.
            }
            socket.getOutputStream().write('{');
            socket.getOutputStream().flush();

            harness.closeProxy();

            assertThat(harness.tasks
                            .submit(() -> {
                                try {
                                    return socket.getInputStream().read() == -1;
                                } catch (SocketException connectionReset) {
                                    return true;
                                }
                            })
                            .get(3, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(harness.accepted.getCount())
                    .as("an incomplete request is never forwarded")
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldTerminateBodyReaderWhenUpstreamStallsAfterAnSseFrame() throws Exception {
        try (Harness harness = new Harness(Reply.STALLED_SSE)) {
            var response = harness.request().get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("content-type")).contains("text/event-stream");
            try (InputStream body = response.body()) {
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
        FINITE_SSE
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService upstreamTasks = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient client = HttpClient.newHttpClient();
        private final HttpServer upstream;
        private final PracticeRunnerLiveLlmTest.ProxyStandIn proxy;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private @Nullable Future<?> closeTask;

        Harness(Reply reply) throws IOException {
            upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            upstream.setExecutor(upstreamTasks);
            upstream.createContext("/", exchange -> reply(exchange, reply));
            upstream.start();
            proxy = new PracticeRunnerLiveLlmTest.ProxyStandIn(new LiveLlmCredentials(
                    "http://127.0.0.1:" + upstream.getAddress().getPort(), "loopback-only", "unused"));
        }

        CompletableFuture<HttpResponse<InputStream>> request() {
            return client.sendAsync(
                    HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/v1/chat/completions"))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }

        void awaitAccepted() throws InterruptedException {
            assertThat(accepted.await(3, TimeUnit.SECONDS)).isTrue();
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
                accepted.countDown();
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
