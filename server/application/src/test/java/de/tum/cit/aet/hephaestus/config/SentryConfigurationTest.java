package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.release.ReleaseProperties;
import de.tum.cit.aet.hephaestus.core.release.RunningRelease;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class SentryConfigurationTest {
    private static RunningRelease staging(String version) {
        return new RunningRelease(version, new ReleaseProperties("", "", true, "staging"), new MockEnvironment());
    }

    @AfterEach
    void closeSentry() {
        Sentry.close();
    }

    @Test
    void initEnforcesThePrivacyContract() {
        var configuration = new SentryConfiguration(
                new MockEnvironment().withProperty("spring.profiles.active", "test"),
                staging("1.2.3"),
                new SentryProperties("https://public@example.invalid/1"));

        configuration.init();

        assertThat(Sentry.getCurrentScopes().getOptions().isSendDefaultPii()).isFalse();
        var event = new SentryEvent();
        event.setUser(new User());
        event.setRequest(new Request());
        event.addBreadcrumb("visited /private/person/42");

        var beforeSend =
                Objects.requireNonNull(Sentry.getCurrentScopes().getOptions().getBeforeSend());
        var scrubbed = beforeSend.execute(event, new Hint());

        assertThat(scrubbed).isNotNull();
        assertThat(scrubbed.getUser()).isNull();
        assertThat(scrubbed.getRequest()).isNull();
        assertThat(scrubbed.getBreadcrumbs()).isNull();
    }

    @Test
    void shouldReportTheDeploymentEnvironmentAndRunningVersionWhenInitialized() {
        // The profile is the same on staging and production, so it must not name the environment.
        new SentryConfiguration(
                        new MockEnvironment().withProperty("spring.profiles.active", "prod"),
                        staging("1.2.3"),
                        new SentryProperties("https://public@example.invalid/1"))
                .init();

        var options = Sentry.getCurrentScopes().getOptions();
        assertThat(options.getEnvironment()).isEqualTo("staging");
        assertThat(options.getRelease()).isEqualTo("1.2.3");
    }
}
