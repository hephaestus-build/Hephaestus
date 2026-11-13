package de.tum.cit.aet.hephaestus.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SlackPropertiesTest extends BaseUnitTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SlackProperties.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    @Test
    void slackItselfIsAcceptedAndSaysNothing() {
        assertThat(new SlackProperties("https://slack.com").apiBase()).isEqualTo("https://slack.com");
        assertThat(new SlackProperties("https://slack.com/").apiBase()).isEqualTo("https://slack.com");
        assertThat(logged.list).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "http://slack-fake.ns.svc.cluster.local:8080,http://slack-fake.ns.svc.cluster.local:8080",
        "http://127.0.0.1:9999/,http://127.0.0.1:9999",
        "https://proxy.example/slack,https://proxy.example",
    })
    void anotherHttpOrHttpsHostIsAcceptedAndWarnedOnceWithItsOriginOnly(String value, String origin) {
        assertThat(new SlackProperties(value).apiBase()).isEqualTo(value.replaceAll("/+$", ""));

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("bot token", "go to that host", origin);
        });
    }

    @Test
    void theWarningNeverRepeatsThePathOfTheBase() {
        new SlackProperties("https://proxy.example/probe-secret/more");

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("https://proxy.example")
                    .doesNotContain("probe-secret");
            assertThat(event.getArgumentArray()).containsExactly("https://proxy.example", "https://slack.com");
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "slack.com",
                "//slack.com",
                "/api",
                "ftp://slack.com",
                "file:///etc/passwd",
                "https://",
                "http:///x",
                "https://user:secret@slack.com",
                "https://slack.com?x=1",
                "https://slack.com#frag",
                "https://sla ck.com",
            })
    void aValueThatIsNotAnHttpOrHttpsUrlWithAHostAndNothingElseStopsTheStart(String value) {
        assertThatThrownBy(() -> new SlackProperties(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hephaestus.integration.slack.api-base")
                .hasMessageContaining("absolute http or https URL");
        assertThat(logged.list).isEmpty();
    }

    @Test
    void theUserInformationOfARefusedValueIsNotRepeatedInTheError() {
        assertThatThrownBy(() -> new SlackProperties("https://user:secret@slack.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("secret");
        assertThat(logged.list).isEmpty();
    }

    private static Binder binderOf(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }

    @Test
    void theServerBindsItFromItsEnvironmentAndDefaultsToSlack() {
        assertThat(binderOf(Map.of(
                                "hephaestus.integration.slack.enabled",
                                "true",
                                "hephaestus.integration.slack.client-id",
                                "c"))
                        .bindOrCreate("hephaestus.integration.slack", SlackProperties.class)
                        .apiBase())
                .isEqualTo("https://slack.com");
        assertThat(binderOf(Map.of("hephaestus.integration.slack.api-base", "http://slack-fake:8080/"))
                        .bindOrCreate("hephaestus.integration.slack", SlackProperties.class)
                        .apiBase())
                .isEqualTo("http://slack-fake:8080");
    }

    @Test
    void aBoundValueThatIsRefusedStopsTheBinding() {
        assertThatThrownBy(() -> binderOf(Map.of("hephaestus.integration.slack.api-base", "slack.com"))
                        .bindOrCreate("hephaestus.integration.slack", SlackProperties.class))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
