package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PublicApiUrlsTest extends BaseUnitTest {
    @ParameterizedTest
    @CsvSource(
            value = {
                "https://example.org | https://example.org/api",
                "https://example.org/api | https://example.org/api",
                "https://example.org/old | https://example.org/api",
                "http://localhost:8080 | http://localhost:8080/api"
            },
            delimiter = '|')
    void shouldUseTheConfiguredOriginAndTheApiPath(String issuer, String expectedBase) {
        var urls = new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create(issuer)));
        assertThat(urls.uri("/items/{id}", "opaque/token").toString())
                .isEqualTo(expectedBase + "/items/opaque%2Ftoken");
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "https://example.org/api | https://example.org",
                "https://example.org/old | https://example.org",
                "http://localhost:8080 | http://localhost:8080"
            },
            delimiter = '|')
    void shouldKeepReceiverPathsOutsideTheApiPrefix(String issuer, String origin) {
        var urls = new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create(issuer)));
        assertThat(urls.originUri("/webhooks/provider/{route}", "opaque/token").toString())
                .isEqualTo(origin + "/webhooks/provider/opaque%2Ftoken");
    }
}
