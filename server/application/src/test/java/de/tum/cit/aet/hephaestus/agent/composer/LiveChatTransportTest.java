package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Each case of a live eval reports only the models that served its own calls, and every request it sent. */
@Tag("unit")
class LiveChatTransportTest {
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer endpoint;

    @BeforeEach
    void startTheEndpoint() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        endpoint.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"model\":\"served-" + calls.incrementAndGet() + "\",\"choices\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        endpoint.start();
    }

    @AfterEach
    void stopTheEndpoint() {
        endpoint.stop(0);
    }

    @Test
    void shouldReportOnlyTheModelsServedSinceTheLastResetWhenACaseStartsANewMeasurement() {
        var transport = new LiveChatTransport(new LiveLlmCredentials(
                "http://127.0.0.1:" + endpoint.getAddress().getPort(), "test-key", "requested"));

        transport.call("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(transport.servedModels()).containsExactly("served-1");

        transport.startCase();
        transport.call("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(transport.servedModels()).containsExactly("served-2");
    }

    @Test
    void shouldCountEveryRequestTheEndpointReceivesAndSendNoSecondRequestAfterANotFound() throws IOException {
        var received = new AtomicInteger();
        var missing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        missing.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        missing.start();
        try {
            var transport = new LiveChatTransport(new LiveLlmCredentials(
                    "http://127.0.0.1:" + missing.getAddress().getPort(), "test-key", "requested"));

            var answer = transport.call("{}".getBytes(StandardCharsets.UTF_8));

            assertThat(answer.status()).isEqualTo(404);
            assertThat(received.get()).isEqualTo(1);
            assertThat(transport.requests()).isEqualTo(received.get());
        } finally {
            missing.stop(0);
        }
    }

    @Test
    void shouldCountAsManyRequestsAsTheEndpointServedWhenEachCallSucceeds() {
        var transport = new LiveChatTransport(new LiveLlmCredentials(
                "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/v1/", "test-key", "requested"));

        for (int call = 0; call < 3; call++) transport.call("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(transport.requests()).isEqualTo(calls.get());
    }
}
