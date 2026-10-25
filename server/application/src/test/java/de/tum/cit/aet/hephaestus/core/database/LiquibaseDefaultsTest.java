package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import liquibase.Scope;
import liquibase.analytics.configuration.AnalyticsArgs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LiquibaseDefaultsTest extends BaseUnitTest {

    // A lookup before Liquibase has started its root scope sees no registered provider; every migration starts
    // that scope first.
    @BeforeAll
    static void startLiquibase() {
        Scope.getCurrentScope();
    }

    @Test
    void shouldSendNoAnalyticsWhenTheOperatorSetsNothing() {
        var configured = AnalyticsArgs.ENABLED.getCurrentConfiguredValue();

        assertThat(configured.getValue()).isFalse();
        assertThat(configured.getProvidedValue().describe()).startsWith("Hephaestus defaults");
    }

    @Test
    void shouldSendAnalyticsWhenTheOperatorOptsInWithASystemProperty() {
        System.setProperty(AnalyticsArgs.ENABLED.getKey(), "true");
        try {
            assertThat(AnalyticsArgs.ENABLED.getCurrentConfiguredValue().getValue())
                    .isTrue();
        } finally {
            System.clearProperty(AnalyticsArgs.ENABLED.getKey());
        }
    }

    // A running JVM cannot change its own environment, so the opt-in is read by a fresh one.
    @Test
    void shouldSendAnalyticsWhenTheOperatorOptsInWithAnEnvironmentVariable() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true);
        process.environment().put("LIQUIBASE_ANALYTICS_ENABLED", "true");
        Process probe = process.start();
        try {
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.exitValue()).as(output).isZero();
            assertThat(output).endsWith("analytics=true");
        } finally {
            probe.destroyForcibly();
        }
    }

    /** Prints what Liquibase decides in the JVM it runs in. */
    public static final class Probe {
        public static void main(String[] args) {
            System.out.print("analytics="
                    + AnalyticsArgs.ENABLED.getCurrentConfiguredValue().getValue());
        }
    }
}
