package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JsonlStdoutPumpTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Built at runtime \u2014 Java's pre-lexer would translate a \\u2028 escape into a real line terminator and break
    // the source.
    private static final String LINE_SEP = Character.toString(0x2028);
    private static final String PARA_SEP = Character.toString(0x2029);
    private static final int LINE_CAP = 1024;

    private record Captured(List<JsonNode> frames, List<Integer> bytes, AtomicInteger eofCalls, AtomicInteger lost) {}

    private Captured runPump(String input) {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter parseErrors = reg.counter("test.parse.error");
        return runPump(input, parseErrors, LINE_CAP);
    }

    private Captured runPump(String input, Counter parseErrors, int lineCap) {
        List<JsonNode> frames = new ArrayList<>();
        List<Integer> bytes = new ArrayList<>();
        AtomicInteger eofCalls = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        JsonlStdoutPump pump = new JsonlStdoutPump(
                UUID.randomUUID(),
                new StringReader(input),
                MAPPER,
                (frame, wireBytes) -> {
                    frames.add(frame);
                    bytes.add(wireBytes);
                },
                lost::incrementAndGet,
                ec -> eofCalls.incrementAndGet(),
                () -> 0,
                parseErrors,
                lineCap,
                Map.of());
        pump.start();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eofCalls.get()).isEqualTo(1));
        return new Captured(frames, bytes, eofCalls, lost);
    }

    @Test
    void parsesFrames() {
        Captured c = runPump("{\"t\":\"a\"}\n{\"t\":\"b\"}\n{\"t\":\"c\"}\n");
        assertThat(c.frames()).hasSize(3);
        assertThat(c.frames().get(0).get("t").asString()).isEqualTo("a");
        assertThat(c.frames().get(2).get("t").asString()).isEqualTo("c");
    }

    @Test
    @DisplayName("U+2028 / U+2029 inside JSON string values do NOT split the frame")
    void unicodeSeparatorsSurvive() {
        // BufferedReader.readLine would split on U+2028/U+2029; our \n-only pump must keep this as one frame.
        String line = "{\"t\":\"echo\",\"p\":\"x" + LINE_SEP + "y" + PARA_SEP + "z\"}\n";
        Captured c = runPump(line);
        assertThat(c.frames()).hasSize(1);
        String decoded = c.frames().get(0).get("p").asString();
        assertThat(decoded).contains(LINE_SEP).contains(PARA_SEP);
        assertThat(LINE_SEP.getBytes(StandardCharsets.UTF_8)).hasSize(3);
        assertThat(PARA_SEP.getBytes(StandardCharsets.UTF_8)).hasSize(3);
    }

    @Test
    @DisplayName("JSON \\n / \\r escapes inside string values decode to real LF / CR, single frame")
    void escapedNewlinesSurvive() {
        // \\n in the JSON source is two source chars (backslash, n) — NOT a real newline.
        String line = "{\"t\":\"echo\",\"p\":\"a\\nb\\rc\"}\n";
        Captured c = runPump(line);
        assertThat(c.frames()).hasSize(1);
        String decoded = c.frames().get(0).get("p").asString();
        assertThat(decoded).isEqualTo("a\nb\rc");
    }

    @Test
    void malformedLineSurvived() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter parseErrors = reg.counter("test.parse.error");
        Captured c = runPump("{\"t\":\"a\"}\n{not json}\n{\"t\":\"c\"}\n", parseErrors, LINE_CAP);
        assertThat(c.frames()).hasSize(2);
        assertThat(c.frames().get(0).get("t").asString()).isEqualTo("a");
        assertThat(c.frames().get(1).get("t").asString()).isEqualTo("c");
        assertThat(parseErrors.count()).isEqualTo(1.0);
        assertThat(c.lost())
                .as("the unreadable line is reported as a lost frame")
                .hasValue(1);
    }

    @Test
    void blankLinesSkipped() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter parseErrors = reg.counter("test.parse.error");
        Captured c = runPump("\n\n{\"t\":\"a\"}\n\n\n{\"t\":\"b\"}\n\n", parseErrors, LINE_CAP);
        assertThat(c.frames()).hasSize(2);
        assertThat(parseErrors.count()).isZero();
        assertThat(c.lost()).hasValue(0);
    }

    @Test
    void oversizedLineRejected() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        Counter parseErrors = reg.counter("test.parse.error");
        String big = "{\"t\":\"" + "x".repeat(200) + "\"}\n{\"t\":\"after\"}\n";
        Captured c = runPump(big, parseErrors, 64);
        assertThat(parseErrors.count()).isGreaterThanOrEqualTo(1.0);
        assertThat(c.frames()).noneMatch(f -> "after".equals(f.get("t").asString()));
        assertThat(c.lost()).hasValue(1);
    }

    @Test
    void rawCarriageReturnBetweenFramesIsNotASplitter() {
        // BufferedReader.readLine splits on CR; our \n-only pump must not. (Jackson is lenient with trailing
        // content after the first complete value — up to one frame may parse, but never two.)
        Captured c = runPump("{\"a\":1}\r{\"b\":2}\n");
        long bFrames = c.frames().stream().filter(n -> n.has("b")).count();
        assertThat(bFrames).as("\\r must not produce a second frame").isZero();
    }

    @Test
    @DisplayName("wireBytes captures exact UTF-8 length plus the terminator newline")
    void byteCounterIsExact() {
        String line = "{\"t\":\"x\"}\n";
        Captured c = runPump(line);
        assertThat(c.bytes()).hasSize(1);
        // The frame is ASCII so chars == UTF-8 bytes; +1 for the \n terminator.
        assertThat(c.bytes().get(0)).isEqualTo("{\"t\":\"x\"}".getBytes(StandardCharsets.UTF_8).length + 1);
    }
}
