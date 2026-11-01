package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerPropertiesConfiguration;
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
 * Each role stops within the grace that Kubernetes gives it: the preStop pause, every shutdown phase at
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
        Duration stop = httpPhase().plus(IntegrationNatsConsumer.SHUTDOWN_WAIT).plus(MARGIN);

        assertThat(chartGrace("server.yaml"))
                .isGreaterThanOrEqualTo(preStop("server.yaml").plus(stop));
    }

    @Test
    void shouldStopTheWebhookReceiverWithinItsGrace() throws IOException {
        Duration stop = httpPhase().plus(applicationDuration("drain-timeout")).plus(MARGIN);

        assertThat(chartGrace("webhook.yaml"))
                .isGreaterThanOrEqualTo(preStop("webhook.yaml").plus(stop));
    }

    @Test
    void shouldStopTheWorkerWithinItsGraceForAnyDrain() throws IOException {
        // The chart's grace is the drain plus a constant, so it fits every drainTimeoutSeconds once the constant
        // covers what the stop adds to the drain.
        Matcher grace = Pattern.compile("terminationGracePeriodSeconds: \\{\\{ add \\$drain (\\d+) }}")
                .matcher(read("deploy/chart/hephaestus/templates/worker.yaml"));
        assertThat(grace.find())
                .as("the worker grace is the drain plus a constant")
                .isTrue();
        Duration beyondDrain = preStop("worker.yaml")
                .plus(WorkerPropertiesConfiguration.drainPhaseTimeout(Duration.ZERO))
                .plus(httpPhase())
                .plus(MARGIN);
        assertThat(Duration.ofSeconds(Long.parseLong(grace.group(1)))).isGreaterThanOrEqualTo(beyondDrain);
    }

    /** The timeout of each shutdown phase except the worker's drain: {@code SHUTDOWN_TIMEOUT}'s default. */
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

    private static Duration chartGrace(String template) throws IOException {
        Matcher matcher = Pattern.compile("terminationGracePeriodSeconds: (\\d+)")
                .matcher(read("deploy/chart/hephaestus/templates/" + template));
        assertThat(matcher.find()).as(template).isTrue();
        return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
    }

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }
}
