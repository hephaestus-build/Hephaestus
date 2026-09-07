package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ReleaseCheckClientTest {
    private final RestClient.Builder builder =
            RestClient.builder().baseUrl("https://api.github.com/repos/hephaestus-build/Hephaestus/releases/latest");
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final ReleaseCheckClient client =
            new ReleaseCheckClient(builder, JsonMapper.builder().build());

    private static String body(String tag, String notes) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"body\":\"" + notes
                + "\",\"published_at\":\"2026-09-07T00:00:00Z\",\"html_url\":\"https://evil.invalid\"}";
    }

    @Test
    void shouldUseSafeReleaseLinkAndPublishedMigrationFlag() {
        server.expect(requestTo("https://api.github.com/repos/hephaestus-build/Hephaestus/releases/latest"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(header("User-Agent", "Hephaestus-release-check"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header("If-None-Match", "\"etag\""))
                .andRespond(withSuccess(
                        body("v1.2.3", "<!-- hephaestus:schema-migrations=true -->"), MediaType.APPLICATION_JSON));
        var release = client.check("\"etag\"").release();
        assertThat(release).isNotNull();
        assertThat(release.schemaMigrations()).isEqualTo("REQUIRED");
        assertThat(release.notesUrl()).startsWith("https://github.com/hephaestus-build/Hephaestus/releases/tag/");
        assertThat(release.securityRelevance()).isEqualTo("UNKNOWN");
        server.verify();
    }

    @Test
    void shouldKeepUnmarkedMigrationMetadataUnknown() {
        server.expect(anything())
                .andRespond(withSuccess(
                        body("v1.2.3", "This release contains **schema migrations**"), MediaType.APPLICATION_JSON));
        var release = client.check(null).release();
        assertThat(release).isNotNull();
        assertThat(release.schemaMigrations()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldReportNoMigrationsOnlyForExplicitPublisherFlag() {
        server.expect(anything())
                .andRespond(withSuccess(
                        body("v1.2.3", "<!-- hephaestus:schema-migrations=false -->"), MediaType.APPLICATION_JSON));
        var release = client.check(null).release();
        assertThat(release).isNotNull();
        assertThat(release.schemaMigrations()).isEqualTo("NONE");
    }

    @Test
    void shouldRejectPrereleaseAndMalformedVersions() {
        for (String tag : new String[] {"v1.0.0-rc.1", "v01.2.3", "v999999999999999.0.0", "not-version"}) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body(tag, "notes"), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.check(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldRejectOversizedResponse() {
        server.expect(anything()).andRespond(withSuccess("x".repeat(262145), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.check(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
