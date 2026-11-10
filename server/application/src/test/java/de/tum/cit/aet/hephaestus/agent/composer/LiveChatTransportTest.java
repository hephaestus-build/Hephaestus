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

/** Each case of a live eval reports only the models that served its own calls. */
@Tag("unit")
class LiveChatTransportTest {
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer endpoint;

    @BeforeEach
    void startTheEndpoint() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        endpoint.createContext("/chat/completions", exchange -> {
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

        transport.resetServedModels();
        transport.call("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(transport.servedModels()).containsExactly("served-2");
    }
}
