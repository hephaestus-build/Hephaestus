package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailUnsubscribeLinksTest extends BaseUnitTest {
    @Test
    void shouldPreserveDeploymentBasePathsAndEncodeTokens() {
        var links = new EmailUnsubscribeLinks(new ApplicationProperties(
                "https://example.org/api", new ApplicationProperties.Webapp("https://example.org/app")));
        assertThat(links.url("opaque/token"))
                .isEqualTo("https://example.org/api/notifications/unsubscribe/opaque%2Ftoken");
        assertThat(links.confirmationUrl("opaque&token"))
                .isEqualTo("https://example.org/app/unsubscribe?token=opaque%26token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"})
    void shouldAllowLoopbackMailpitLinks(String base) {
        var links = new EmailUnsubscribeLinks(new ApplicationProperties(base, new ApplicationProperties.Webapp(base)));
        assertThat(links.url("token")).startsWith(base + "/notifications/unsubscribe/");
        assertThat(links.confirmationUrl("token")).startsWith(base + "/unsubscribe?");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.org",
                "http://localhost.example.org",
                "https://user:secret@example.org",
                "https://example.org#fragment",
                "https://example.org?query=true",
                "ftp://example.org"
            })
    void shouldRejectUnsafePublicUrlsForBothUnsubscribeSurfaces(String base) {
        var links = new EmailUnsubscribeLinks(new ApplicationProperties(base, new ApplicationProperties.Webapp(base)));
        assertThatThrownBy(() -> links.url("token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.confirmationUrl("token")).isInstanceOf(IllegalArgumentException.class);
    }
}
