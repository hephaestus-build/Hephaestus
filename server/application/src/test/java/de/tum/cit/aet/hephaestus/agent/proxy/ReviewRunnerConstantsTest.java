package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy sizes a review's calls by two values that the review runner owns. A change on either side fails here
 * until the other follows it.
 */
class ReviewRunnerConstantsTest extends BaseUnitTest {

    private static final Path RUNNER = Path.of("src/main/resources/agent/pi-runner.ts");

    private static final Path SCRIPT_CONCURRENCY =
            Path.of("../../docker/agents/practice-scripts/lib/core/concurrency.ts");

    @Test
    @DisplayName("a silent model call ends when its review's runner gives up on the silent session")
    void shouldEndASilentCallWhenTheRunnerGivesUpOnTheSession() throws IOException {
        long stallMs = constant(RUNNER, "STALL_MS");

        assertThat(LlmProxyWebClientConfig.IDLE.toMillis()).isEqualTo(stallMs);
    }

    @Test
    @DisplayName("the cap counts the direct model calls that the practice scripts run at once")
    void shouldCountTheScriptsConcurrencyWhenTheCapHoldsScriptCalls() throws IOException {
        long scriptCalls = constant(SCRIPT_CONCURRENCY, "MODEL_CONCURRENCY");

        assertThat(ProxyCallsInFlight.SCRIPT_CALLS).isEqualTo(scriptCalls);
    }

    private static long constant(Path file, String name) throws IOException {
        Matcher matcher = Pattern.compile("const " + name + " = ([0-9_]+);").matcher(Files.readString(file));
        assertThat(matcher.find()).as(name + " in " + file).isTrue();
        return Long.parseLong(matcher.group(1).replace("_", ""));
    }
}
