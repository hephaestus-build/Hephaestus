package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailUnsubscribeLinksTest extends BaseUnitTest {
    @Test
    void shouldPreserveDeploymentBasePathsAndEncodeTokens() {
        var links = links("https://example.org/api", "https://example.org/app");
        assertThat(links.url("opaque/token"))
                .isEqualTo("https://example.org/api/notifications/unsubscribe/opaque%2Ftoken");
        assertThat(links.confirmationUrl("opaque&token"))
                .isEqualTo("https://example.org/app/unsubscribe?token=opaque%26token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"})
    void shouldAllowLoopbackMailpitLinks(String base) {
        var links = links(base, base);
        assertThat(links.url("token")).startsWith(base + "/api/notifications/unsubscribe/");
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
        assertThatThrownBy(() -> links(base, "https://example.org").url("token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links("https://example.org", base).confirmationUrl("token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUseThePublicApiPrefixWhenTheNotificationBaseIsTheInstanceOrigin() {
        var links = links("https://example.org", "https://example.org");
        assertThat(links.url("token")).isEqualTo("https://example.org/api/notifications/unsubscribe/token");
        assertThat(links.confirmationUrl("token")).isEqualTo("https://example.org/unsubscribe?token=token");
    }

    @Test
    void shouldKeepApiAndWebappOriginsSeparateInLocalDevelopment() {
        var properties = new ApplicationProperties(
                "http://localhost:4200", new ApplicationProperties.Webapp("http://localhost:4200"));
        var links = new EmailUnsubscribeLinks(
                properties, new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create("http://localhost:8080"))));
        assertThat(links.url("token")).isEqualTo("http://localhost:8080/api/notifications/unsubscribe/token");
        assertThat(links.confirmationUrl("token")).isEqualTo("http://localhost:4200/unsubscribe?token=token");
    }

    private static EmailUnsubscribeLinks links(String server, String webapp) {
        var properties = new ApplicationProperties(server, new ApplicationProperties.Webapp(webapp));
        return new EmailUnsubscribeLinks(
                properties, new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create(server))));
    }
}
