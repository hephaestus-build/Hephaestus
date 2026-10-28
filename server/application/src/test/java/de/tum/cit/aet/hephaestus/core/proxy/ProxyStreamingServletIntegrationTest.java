package de.tum.cit.aet.hephaestus.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Real servlet lifetime; the held write is deterministic backpressure, not a socket-timeout claim. */
@Tag("integration")
class ProxyStreamingServletIntegrationTest {

    private static final String FRAME = "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}\n\n";

    @Test
    void shouldKeepWritesRequestOwnedWhenTheUpstreamDeadlinePassesDuringAWrite() throws Exception {
        var state = new ProofState();
        Flux<Publisher<? extends ByteBuf>> groups = Flux.concat(
                Mono.just(Mono.fromSupplier(() -> Unpooled.copiedBuffer(FRAME, StandardCharsets.UTF_8))), Flux.never());
        var upstream = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    state.requests.incrementAndGet();
                    return response.header("Content-Type", "text/event-stream")
                            .sendGroups(groups.doOnCancel(state.cancelled::countDown));
                })
                .bindNow(Duration.ofSeconds(10));
        state.upstream = URI.create("http://127.0.0.1:" + upstream.port() + "/completion");
        var application = new SpringApplication(ServletApplication.class);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("proofState", state));
        try (var context = application.run(
                        "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off");
                var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stream-proof"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            try {
                assertThat(state.writing.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(state.cancelled.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(state.returned.getCount())
                        .as("the real servlet still owns its held write")
                        .isEqualTo(1);
                assertThat(state.violation.get()).isNull();
                assertThat(state.requests).hasValue(1);

                state.release.countDown();
                var received = response.get(10, TimeUnit.SECONDS);
                try (var body = received.body()) {
                    assertThat(received.statusCode()).isEqualTo(200);
                    assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
                            .isEqualTo(FRAME);
                }
                assertThat(state.returned.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(state.outcome.get()).isEqualTo(ProxyStreamingUtils.StreamOutcome.TIMED_OUT);
                assertThat(state.observed.toString(StandardCharsets.UTF_8)).isEqualTo(FRAME);
                assertThat(state.violation.get()).isNull();
                assertThat(state.requests).hasValue(1);
            } finally {
                state.release.countDown();
                if (response.isDone() && !response.isCompletedExceptionally() && !response.isCancelled()) {
                    var completed = response.getNow(null);
                    if (completed != null) completed.body().close();
                }
                response.cancel(true);
            }
        } finally {
            state.release.countDown();
            upstream.disposeNow(Duration.ofSeconds(10));
        }
    }

    private static class ProofState {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<@Nullable String> violation = new AtomicReference<>();
        final AtomicReference<ProxyStreamingUtils.@Nullable StreamOutcome> outcome = new AtomicReference<>();
        final ByteArrayOutputStream observed = new ByteArrayOutputStream();

        @Nullable
        URI upstream;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class ServletApplication {
        @Bean
        ServletRegistrationBean<HttpServlet> proofServlet(ProofState state) {
            return new ServletRegistrationBean<>(new ProofServlet(state), "/stream-proof");
        }
    }

    private static class ProofServlet extends HttpServlet {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient ProofState state;

        ProofServlet(ProofState state) {
            this.state = state;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            var guarded = new OwnedResponse(response, state);
            try {
                var exchange = WebClient.create()
                        .get()
                        .uri(Objects.requireNonNull(state.upstream))
                        .exchangeToFlux(upstream -> ProxyStreamingUtils.parts(upstream));
                var result = ProxyStreamingUtils.forward(
                        exchange, Duration.ofSeconds(3), guarded, state.observed::writeBytes);
                state.outcome.set(result.streamOutcome());
            } finally {
                state.returned.countDown();
            }
        }
    }

    private static class OwnedResponse extends HttpServletResponseWrapper {
        private final Thread owner = Thread.currentThread();
        private final ProofState state;

        OwnedResponse(HttpServletResponse response, ProofState state) {
            super(response);
            this.state = state;
        }

        private void checkOwner() {
            if (!Thread.currentThread().equals(owner) || state.returned.getCount() == 0) {
                state.violation.compareAndSet(null, "servlet access outside its owning request");
                throw new IllegalStateException("servlet access outside its owning request");
            }
        }

        @Override
        public void setStatus(int status) {
            checkOwner();
            super.setStatus(status);
        }

        @Override
        public void setContentType(@Nullable String type) {
            checkOwner();
            super.setContentType(type);
        }

        @Override
        public void setCharacterEncoding(@Nullable String charset) {
            checkOwner();
            super.setCharacterEncoding(charset);
        }

        @Override
        public void setHeader(String name, @Nullable String value) {
            checkOwner();
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, @Nullable String value) {
            checkOwner();
            super.addHeader(name, value);
        }

        @Override
        public void flushBuffer() throws IOException {
            checkOwner();
            super.flushBuffer();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            checkOwner();
            var delegate = super.getOutputStream();
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    checkOwner();
                    return delegate.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    checkOwner();
                    delegate.setWriteListener(listener);
                }

                @Override
                public void write(int value) throws IOException {
                    checkOwner();
                    delegate.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    checkOwner();
                    state.writing.countDown();
                    try {
                        if (!state.release.await(15, TimeUnit.SECONDS))
                            throw new IOException("held write was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("held write interrupted", interrupted);
                    }
                    checkOwner();
                    delegate.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    checkOwner();
                    delegate.flush();
                }
            };
        }
    }
}
