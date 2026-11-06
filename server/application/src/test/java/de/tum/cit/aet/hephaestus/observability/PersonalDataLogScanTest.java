package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PersonalDataLogScanTest {

    private static final String LOGGER = "de.tum.cit.aet.hephaestus.Example";

    private static String record(String message) {
        return "{\"@timestamp\":\"2026-11-05T00:00:00Z\",\"log.level\":\"INFO\",\"event.name\":\"example.done\","
                + "\"message\":\"" + message + "\"}";
    }

    @Test
    void shouldReportEachKindByLoggerEventAndKindOnly() {
        var secrets = List.of(
                "mail jordan@example.com",
                "token ghp_" + "a".repeat(36),
                "token glpat-" + "b".repeat(20),
                "token xoxb-" + "1234567890-abcdef",
                "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXZhbHVl",
                "header Bearer " + "c".repeat(32),
                "assigned Max E. Muster");
        var kinds =
                List.of("email", "github_token", "gitlab_token", "slack_token", "jwt", "bearer", "fixture_person_name");

        for (int i = 0; i < secrets.size(); i++) {
            var found = PersonalDataLogScan.scan(LOGGER, record(secrets.get(i)));
            assertThat(found).contains(LOGGER + " | example.done | " + kinds.get(i));
            // The report keeps where the match came from, never the matched text.
            assertThat(String.join("\n", found))
                    .doesNotContain(secrets.get(i).substring(secrets.get(i).indexOf(' ') + 1));
        }
    }

    @Test
    void shouldScanAgainOnceAttachedAfterSpringBootReconfiguresLogging() {
        var context = new LoggerContext();
        var scan = new PersonalDataLogScan();
        scan.install(context);
        context.getLogger(LOGGER).info("before the reconfiguration");
        // What Spring Boot does for each application context: the stop detaches and stops every appender.
        context.stop();
        context.reset();
        scan.attach(context);
        context.getLogger(LOGGER).info("after the reconfiguration");
        assertThat(scan.records()).isEqualTo(2);
    }

    @Test
    void shouldStayQuietOnRecordsWithoutPersonalDataOrCredentials() {
        assertThat(PersonalDataLogScan.scan(LOGGER, record("Synced 12 repositories for workspace 4 in 2.3 s")))
                .isEmpty();
        // A common word that is part of a fixture name is no match: only the whole name is.
        assertThat(PersonalDataLogScan.scan(LOGGER, record("The test user count is 3, reviewer one of two")))
                .isEmpty();
        assertThat(PersonalDataLogScan.scan(LOGGER, "{\"message\":\"no event\"}"))
                .isEmpty();
        // A Message-ID: a random UUID at the sender's domain.
        assertThat(PersonalDataLogScan.scan(
                        LOGGER,
                        record("email: sent kind=digest messageId=<3f2b9c1e-8a4d-4f6b-9e2a-1c7d5b8e0f3a@example.com>")))
                .isEmpty();
    }
}
