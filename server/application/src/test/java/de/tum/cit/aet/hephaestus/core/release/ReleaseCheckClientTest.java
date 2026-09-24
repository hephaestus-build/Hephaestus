package de.tum.cit.aet.hephaestus.core.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Failed;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.Found;
import de.tum.cit.aet.hephaestus.core.release.ReleaseCheckClient.NotModified;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@Tag("unit")
class ReleaseCheckClientTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String LATEST = "https://api.github.com/repos/hephaestus-build/Hephaestus/releases/latest";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final ReleaseCheckClient client = new ReleaseCheckClient(builder, Clock.fixed(NOW, ZoneOffset.UTC));

    private static String release(String tag, String notes) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"body\":\"" + notes
                + "\",\"published_at\":\"2026-09-07T00:00:00Z\",\"html_url\":\"https://evil.invalid\"}";
    }

    @Test
    void shouldSendOneConditionalUnauthenticatedRequestAndReadPublishedFlag() {
        server.expect(requestTo(LATEST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(header(HttpHeaders.USER_AGENT, "Hephaestus-release-check"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "W/\"etag\""))
                .andRespond(withSuccess(
                                release("v1.2.3", "<!-- hephaestus:schema-migrations=true -->"),
                                MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "W/\"next\""));
        var found = (Found) client.fetchLatest("W/\"etag\"");
        assertThat(found.etag()).isEqualTo("W/\"next\"");
        assertThat(found.release().version()).isEqualTo("1.2.3");
        assertThat(found.release().publishedAt()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(found.release().notesUrl())
                .isEqualTo("https://github.com/hephaestus-build/Hephaestus/releases/tag/v1.2.3");
        assertThat(found.release().schemaMigrations()).isTrue();
        server.verify();
    }

    @Test
    void shouldLeaveMigrationsUnknownUnlessThePublisherFlagIsPresent() {
        server.expect(anything())
                .andRespond(withSuccess(release("v1.2.3", "schema migrations ahead"), MediaType.APPLICATION_JSON));
        assertThat(((Found) client.fetchLatest(null)).release().schemaMigrations())
                .isNull();
        server.reset();
        server.expect(anything())
                .andRespond(withSuccess(
                        release("v1.2.3", "<!-- hephaestus:schema-migrations=false -->"), MediaType.APPLICATION_JSON));
        assertThat(((Found) client.fetchLatest(null)).release().schemaMigrations())
                .isFalse();
    }

    @Test
    void shouldReportNotModifiedWhenGitHubConfirmsTheEtag() {
        server.expect(header(HttpHeaders.IF_NONE_MATCH, "\"etag\"")).andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        assertThat(client.fetchLatest("\"etag\"")).isInstanceOf(NotModified.class);
    }

    @Test
    void shouldRejectDraftsPrereleasesUnboundedTagsAndNonJsonAsMalformed() {
        var malformed = new java.util.ArrayList<String>();
        for (String tag : new String[] {"v1.0.0-rc.1", "v01.2.3", "v9999999999.0.0", "1.2.3", "main"}) {
            malformed.add(release(tag, ""));
        }
        malformed.add(release("v1.2.3", "").replace("\"prerelease\":false", "\"prerelease\":true"));
        malformed.add(release("v1.2.3", "").replace("\"draft\":false", "\"draft\":true"));
        malformed.add("not json");
        for (String body : malformed) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThat(client.fetchLatest(null)).as(body).isEqualTo(new Failed(ReleaseCheckFailure.MALFORMED, null));
        }
    }

    @Test
    void shouldHonourRetryAfterSecondsAndDatesWhenRateLimited() {
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7200"));
        assertThat(client.fetchLatest(null))
                .isEqualTo(new Failed(ReleaseCheckFailure.RATE_LIMITED, NOW.plusSeconds(7200)));
        server.reset();
        server.expect(anything())
                .andRespond(
                        withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "Tue, 8 Sep 2026 02:00:00 GMT"));
        assertThat(client.fetchLatest(null))
                .isEqualTo(new Failed(ReleaseCheckFailure.RATE_LIMITED, NOW.plusSeconds(7200)));
    }

    @Test
    void shouldReadTheExhaustedPrimaryLimitFromTheResetHeaderAndFallBackForGarbage() {
        long reset = NOW.plusSeconds(10_800).getEpochSecond();
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", Long.toString(reset)));
        assertThat(client.fetchLatest(null))
                .isEqualTo(new Failed(ReleaseCheckFailure.RATE_LIMITED, Instant.ofEpochSecond(reset)));
        server.reset();
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60")
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", Long.toString(reset)));
        assertThat(client.fetchLatest(null))
                .isEqualTo(new Failed(ReleaseCheckFailure.RATE_LIMITED, Instant.ofEpochSecond(reset)));
        server.reset();
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "soon"));
        assertThat(client.fetchLatest(null))
                .isEqualTo(new Failed(ReleaseCheckFailure.RATE_LIMITED, NOW.plusSeconds(86_400)));
    }

    @Test
    void shouldNotMistakeOrdinaryRefusalsAndOutagesForRateLimits() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(client.fetchLatest(null)).isEqualTo(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
        server.reset();
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(client.fetchLatest(null)).isEqualTo(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
        server.reset();
        server.expect(anything()).andRespond(withException(new IOException("connection reset")));
        assertThat(client.fetchLatest(null)).isEqualTo(new Failed(ReleaseCheckFailure.UNAVAILABLE, null));
    }
}
