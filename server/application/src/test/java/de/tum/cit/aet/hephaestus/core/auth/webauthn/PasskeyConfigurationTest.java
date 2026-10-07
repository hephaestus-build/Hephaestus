package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PasskeyConfigurationTest {
    @Test
    void shouldAcceptExactHTTPSOrigin() {
        assertThat(PasskeyConfiguration.origin(
                        URI.create("https://hephaestus.example:8443"), "hephaestus.example", true))
                .isEqualTo("https://hephaestus.example:8443");
    }

    @Test
    void shouldRejectHTTPInProduction() {
        assertThatThrownBy(() -> PasskeyConfiguration.origin(URI.create("http://localhost"), "localhost", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectSiblingDomain() {
        assertThatThrownBy(
                        () -> PasskeyConfiguration.origin(URI.create("https://evil-example.com"), "example.com", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectPathsAndCredentials() {
        assertThatThrownBy(
                        () -> PasskeyConfiguration.origin(URI.create("https://example.com/api"), "example.com", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> PasskeyConfiguration.origin(URI.create("https://user@example.com"), "example.com", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPermitLocalHTTPOnlyOutsideProduction() {
        assertThat(PasskeyConfiguration.origin(URI.create("http://localhost:4200"), "localhost", false))
                .isEqualTo("http://localhost:4200");
    }
}
