package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient.UserInstallation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAppUserAuthorizationClientTest extends BaseUnitTest {

    private MockWebServer github;
    private GitHubAppUserAuthorizationClient client;

    @BeforeEach
    void setUp() throws IOException {
        github = new MockWebServer();
        github.start();
        client = client("Iv1.client", "client-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        github.close();
    }

    @Test
    void shouldExchangeTheCodeWithTheAppsClientCredentials() throws Exception {
        respond(200, "{\"access_token\":\"ghu_user\",\"token_type\":\"bearer\",\"scope\":\"\"}");

        assertThat(client.exchangeCode("code-1")).isEqualTo("ghu_user");

        RecordedRequest request = github.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getTarget()).isEqualTo("/login/oauth/access_token");
        assertThat(request.getHeaders().get("Accept")).isEqualTo("application/json");
        assertThat(body(request)).isEqualTo("client_id=Iv1.client&client_secret=client-secret&code=code-1");
    }

    @Test
    void shouldFailWhenGitHubRefusesTheCodeWithASuccessfulStatus() {
        respond(200, "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect.\"}");

        assertThatThrownBy(() -> client.exchangeCode("reused"))
                .isInstanceOf(GitHubUserAuthorizationException.class)
                .hasMessageContaining("bad_verification_code");
    }

    @Test
    void shouldFailWhenGitHubCannotBeReachedForTheExchange() {
        respond(502, "bad gateway");

        assertThatThrownBy(() -> client.exchangeCode("code-1")).isInstanceOf(GitHubUserAuthorizationException.class);
    }

    @Test
    void shouldRefuseToExchangeWithoutClientCredentials() {
        GitHubAppUserAuthorizationClient unconfigured = client(null, "client-secret");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.exchangeCode("code-1"))
                .isInstanceOf(GitHubUserAuthorizationException.class)
                .hasMessageContaining("not configured");
        assertThat(github.getRequestCount()).isZero();
    }

    @Test
    void shouldFindAnInstallationOnALaterPageAsTheUser() throws Exception {
        respond(200, installations(LongStream.rangeClosed(1, 100)));
        respond(200, """
                {"total_count":101,"installations":[
                  {"id":4242,"target_type":"Organization","account":{"id":77,"login":"acme","type":"Organization"}}]}
                """);

        assertThat(client.findAccessibleInstallation("ghu_user", 4242L))
                .contains(new UserInstallation(4242L, "Organization", new UserInstallation.Account(77L, "acme")));

        RecordedRequest first = github.takeRequest();
        assertThat(first.getTarget()).isEqualTo("/user/installations?per_page=100&page=1");
        assertThat(first.getHeaders().get("Authorization")).isEqualTo("Bearer ghu_user");
        assertThat(github.takeRequest().getTarget()).isEqualTo("/user/installations?per_page=100&page=2");
    }

    @Test
    void shouldNotFindAnInstallationTheUserCannotAccess() {
        respond(200, installations(LongStream.of(1, 2)));

        assertThat(client.findAccessibleInstallation("ghu_user", 4242L)).isEmpty();
        assertThat(github.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldFailWhenGitHubRejectsTheUserToken() {
        respond(401, "{\"message\":\"Bad credentials\"}");

        assertThatThrownBy(() -> client.findAccessibleInstallation("expired", 4242L))
                .isInstanceOf(GitHubUserAuthorizationException.class);
    }

    @Test
    void shouldTreatOnlyAnActiveAdministratorAsTheOwnerOfAnOrganization() throws Exception {
        respond(200, "{\"role\":\"admin\",\"state\":\"active\"}");
        respond(200, "{\"role\":\"member\",\"state\":\"active\"}");
        respond(200, "{\"role\":\"admin\",\"state\":\"pending\"}");
        respond(404, "{\"message\":\"Not Found\"}");
        respond(403, "{\"message\":\"blocked\"}");

        assertThat(client.ownsOrganization("ghu_user", "acme")).isTrue();
        assertThat(client.ownsOrganization("ghu_user", "acme")).isFalse();
        assertThat(client.ownsOrganization("ghu_user", "acme")).isFalse();
        assertThat(client.ownsOrganization("ghu_user", "acme")).isFalse();
        assertThat(client.ownsOrganization("ghu_user", "acme")).isFalse();
        assertThat(github.takeRequest().getTarget()).isEqualTo("/user/memberships/orgs/acme");
    }

    @Test
    void shouldFailWhenGitHubCannotAnswerForTheMembership() {
        respond(500, "{}");

        assertThatThrownBy(() -> client.ownsOrganization("ghu_user", "acme"))
                .isInstanceOf(GitHubUserAuthorizationException.class);
    }

    @Test
    void shouldReadTheUsersId() {
        respond(200, "{\"id\":31,\"login\":\"octocat\"}");

        assertThat(client.userId("ghu_user")).isEqualTo(31L);
    }

    private GitHubAppUserAuthorizationClient client(@Nullable String clientId, @Nullable String clientSecret) {
        String base = github.url("/").toString().replaceAll("/$", "");
        return new GitHubAppUserAuthorizationClient(
                new GitHubProperties(
                        new GitHubProperties.App(1, null, null, null, clientId, clientSecret),
                        new GitHubProperties.Meta(null)),
                base,
                base);
    }

    private void respond(int status, String body) {
        github.enqueue(new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private static String installations(LongStream ids) {
        return ids.mapToObj(id -> "{\"id\":" + id
                        + ",\"target_type\":\"Organization\",\"account\":{\"id\":" + id + ",\"login\":\"org-" + id
                        + "\"}}")
                .collect(Collectors.joining(",", "{\"installations\":[", "]}"));
    }

    private static String body(RecordedRequest request) {
        var body = request.getBody();
        assertThat(body).isNotNull();
        return body == null ? "" : body.utf8();
    }
}
