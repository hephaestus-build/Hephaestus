package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The proxy sizes a review's calls by a value that the review runner owns. A change on either side fails here until
 * the other follows it.
 */
class ReviewRunnerConstantsTest extends BaseUnitTest {

    private static final Path RUNNER = Path.of("src/main/resources/agent/pi-runner.ts");

    @Test
    @DisplayName("a silent model call ends when its review's runner gives up on the silent session")
    void shouldEndASilentCallWhenTheRunnerGivesUpOnTheSession() throws IOException {
        long stallMs = constant(RUNNER, "STALL_MS");

        assertThat(LlmProxyWebClientConfig.IDLE.toMillis()).isEqualTo(stallMs);
    }

    @Test
    @DisplayName("the gateway's rate lets a review renew its most calls in flight every 15 seconds")
    void shouldLetAReviewRenewItsCallsWhenTheGatewayLimitsItsRate() throws NoSuchMethodException {
        var parameter = SandboxGatewayProperties.class.getRecordComponents()[2];
        String rate = parameter
                .getAccessor()
                .getDeclaringClass()
                .getDeclaredConstructors()[0]
                .getParameters()[2]
                .getAnnotation(DefaultValue.class)
                .value()[0];

        assertThat(parameter.getName()).isEqualTo("requestsPerMinute");
        assertThat(Integer.parseInt(rate)).isEqualTo(4 * ProxyCallsInFlight.REVIEW_CALLS);
    }

    private static long constant(Path file, String name) throws IOException {
        Matcher matcher = Pattern.compile("const " + name + " = ([0-9_]+);").matcher(Files.readString(file));
        assertThat(matcher.find()).as(name + " in " + file).isTrue();
        return Long.parseLong(matcher.group(1).replace("_", ""));
    }
}
