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
                "https://example.org | /api | https://example.org/api",
                "https://example.org/api | /api | https://example.org/api",
                "https://example.org/old | /proxy/api/ | https://example.org/proxy/api",
                "http://localhost:8080 | '' | http://localhost:8080"
            },
            delimiter = '|')
    void shouldUseTheConfiguredApiOriginAndPrefix(String issuer, String prefix, String expectedBase) {
        var urls = new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(issuer), prefix));
        assertThat(urls.uri("/items/{id}", "opaque/token").toString())
                .isEqualTo(expectedBase + "/items/opaque%2Ftoken");
    }
}
