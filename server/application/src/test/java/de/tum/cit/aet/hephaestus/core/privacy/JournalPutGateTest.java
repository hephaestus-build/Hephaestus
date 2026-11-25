package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JournalPutGateTest {

    private static final String PROBE =
            "PUT /test-archive/privacy-journal/_checks/probe.json HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}";
    private static final String FACT_LINE = "PUT /test-archive/privacy-journal/2026-11-25/0193a1b2-fact.json HTTP/1.1";
    private static final String FACT = FACT_LINE + "\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\nbody";

    @Test
    void shouldHoldTheWholeRequestLineAndForwardTheExactBytesWhenTcpSplitsTheRequest() throws Exception {
        try (var bucket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                var gate = new JournalPutGate(URI.create("http://127.0.0.1:" + bucket.getLocalPort()))) {
            var received = CompletableFuture.supplyAsync(() -> receive(bucket, PROBE.length() + FACT.length()));
            try (var store =
                    new Socket(gate.endpoint().getHost(), gate.endpoint().getPort())) {
                var out = store.getOutputStream();
                // The probe, then the fact's PUT cut inside its method and its key, then its body on its own.
                var writes = List.of(PROBE, "PU", FACT.substring(2, 40), FACT.substring(40, FACT.length() - 4), "body");
                for (var part : writes) {
                    out.write(part.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }

                assertThat(gate.awaitHeld(Duration.ofSeconds(10))).isEqualTo(FACT_LINE);
                assertThatThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                gate.release();
                assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(PROBE + FACT);
            }
        }
    }

    private static String receive(ServerSocket bucket, int length) {
        try (var connection = bucket.accept()) {
            var in = connection.getInputStream();
            var bytes = new ByteArrayOutputStream();
            while (bytes.size() < length) {
                int next = in.read();
                if (next == -1) break;
                bytes.write(next);
            }
            return bytes.toString(StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
