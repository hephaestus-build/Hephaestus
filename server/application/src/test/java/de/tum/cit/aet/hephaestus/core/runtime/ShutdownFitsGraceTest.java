package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.gateway.AgentShutdownConfiguration;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * The server stops within the grace that Kubernetes gives it: the preStop pause, every shutdown phase at
 * its timeout, the waits that run after the phases, and a margin for JPA, Hikari and the JVM's exit. The grace values
 * live in the chart, the phases in the code and {@code application.yml}; this test keeps them
 * together.
 */
class ShutdownFitsGraceTest extends BaseUnitTest {

    private static final Path ROOT = Path.of("../..");

    /** JPA, Hikari and the JVM's exit after the last phase. */
    private static final Duration MARGIN = Duration.ofSeconds(5);

    @Test
    void shouldStopTheServerWithinItsGrace() throws IOException {
        Duration stop = preStop("server.yaml")
                .plus(httpPhase())
                .plus(applicationDuration("drain-timeout"))
                .plus(IntegrationNatsConsumer.SHUTDOWN_WAIT)
                .plus(MARGIN);

        assertThat(Duration.ofSeconds(Long.parseLong(serverGrace().group(2))))
                .as("the server without sandboxes")
                .isGreaterThanOrEqualTo(stop);
    }

    @Test
    void shouldStopTheServerWithSandboxesWithinItsGraceForAnyDrain() throws IOException {
        // With sandboxes the grace is the drain plus a constant, so it fits every drainTimeoutSeconds once the
        // constant covers what the stop adds to the drain.
        Duration beyondDrain = preStop("server.yaml")
                .plus(AgentShutdownConfiguration.drainPhaseTimeout(Duration.ZERO))
                .plus(httpPhase())
                .plus(applicationDuration("drain-timeout"))
                .plus(IntegrationNatsConsumer.SHUTDOWN_WAIT)
                .plus(MARGIN);

        assertThat(Duration.ofSeconds(Long.parseLong(serverGrace().group(1)))).isGreaterThanOrEqualTo(beyondDrain);
    }

    /** The server's grace: group 1 is the constant added to the drain with sandboxes, group 2 the grace without. */
    private static Matcher serverGrace() throws IOException {
        Matcher grace = Pattern.compile("\\$grace := ternary \\(add \\$drain (\\d+)\\) (\\d+) \\$sandbox\\.enabled }}")
                .matcher(read("deploy/chart/hephaestus/templates/server.yaml"));
        assertThat(grace.find())
                .as("the server grace is the drain plus a constant with sandboxes, else a constant")
                .isTrue();
        return grace;
    }

    /** The timeout of each shutdown phase except the review drain: {@code SHUTDOWN_TIMEOUT}'s default. */
    private static Duration httpPhase() throws IOException {
        Matcher matcher = Pattern.compile("timeout-per-shutdown-phase: \\$\\{SHUTDOWN_TIMEOUT:([^}]+)}")
                .matcher(read("server/application/src/main/resources/application.yml"));
        assertThat(matcher.find()).isTrue();
        return DurationStyle.detectAndParse(matcher.group(1));
    }

    private static Duration applicationDuration(String key) throws IOException {
        Matcher matcher = Pattern.compile("\\n\\s+" + Pattern.quote(key) + ": (\\S+)\\n")
                .matcher(read("server/application/src/main/resources/application.yml"));
        assertThat(matcher.find()).as(key).isTrue();
        return DurationStyle.detectAndParse(matcher.group(1));
    }

    /** The preStop pause of a role whose template includes it, else none. */
    private static Duration preStop(String template) throws IOException {
        if (!read("deploy/chart/hephaestus/templates/" + template).contains("include \"hephaestus.preStop\"")) {
            return Duration.ZERO;
        }
        Matcher matcher = Pattern.compile("preStop:\\s+sleep:\\s+seconds: (\\d+)")
                .matcher(read("deploy/chart/hephaestus/templates/_helpers.tpl"));
        assertThat(matcher.find()).isTrue();
        return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
    }

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }
}
