package de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationSuspendedException;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitHubProductFeedbackIssuesTest extends BaseUnitTest {
    private static final Repository REPOSITORY = new Repository(42, "R_repo", "owner/repo", 7, true);
    private static final Instant SINCE = Instant.parse("2026-10-30T00:00:00Z");
    private static final String ISSUE = """
            {"number":12,"node_id":"I_issue","html_url":"https://github.com/owner/repo/issues/12",
             "body":"report <!-- marker -->","user":{"login":"hephaestus[bot]"}}
            """;

    @Mock
    private GitHubAppTokenService tokens;

    private MockWebServer github;
    private GitHubProductFeedbackIssues adapter;

    @BeforeEach
    void setUp() throws IOException {
        github = new MockWebServer();
        github.start();
        adapter = new GitHubProductFeedbackIssues(
                tokens,
                new OutboundEgressGuard(() -> false),
                RestClient.builder(),
                github.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        github.close();
    }

    @Test
    void shouldReturnUnknownWhenWriteReceivesServerError() {
        installationToken();
        respond(502, "upstream failure");
        assertThatThrownBy(() -> adapter.create(REPOSITORY, "title", "body", null))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class)
                .hasMessageContaining("502");
        assertThat(github.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldRefuseWhenWriteReceivesValidationError() {
        installationToken();
        respond(422, "{\"message\":\"secret body text\"}");
        assertThatThrownBy(() -> adapter.create(REPOSITORY, "title", "body", null))
                .isInstanceOf(ProductFeedbackIssues.Refused.class)
                .hasMessageContaining("422")
                .hasMessageNotContaining("secret body text");
    }

    @Test
    void shouldReturnUnknownWithRetryTimeWhenRateLimited() {
        installationToken();
        github.enqueue(new MockResponse.Builder()
                .code(403)
                .addHeader("x-ratelimit-remaining", "0")
                .addHeader("x-ratelimit-reset", "1793404800")
                .body("{}")
                .build());
        assertThatThrownBy(() -> adapter.comment(REPOSITORY, 12, "body"))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class)
                .hasMessageContaining("Retry at 2026-10-31T00:00:00Z");
    }

    @Test
    void shouldReturnUnknownWhenRetryAfterNamesSecondaryRateLimit() {
        installationToken();
        github.enqueue(new MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "120")
                .body("{}")
                .build());
        assertThatThrownBy(() -> adapter.edit(REPOSITORY, 12, "title", "body"))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class)
                .hasMessageContaining("Retry at");
    }

    @Test
    void shouldRefuseWhenForbiddenWithoutRateLimit() {
        installationToken();
        respond(403, "{}");
        assertThatThrownBy(() -> adapter.close(REPOSITORY, 12, "completed"))
                .isInstanceOf(ProductFeedbackIssues.Refused.class);
    }

    @Test
    void shouldFindMarkerOnLaterPageAndCacheAppSlug() throws Exception {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        page("[]", "/repos/renamed/current/issues?page=2");
        respond(200, "[" + ISSUE + "]");
        assertThat(adapter.findByMarker(REPOSITORY, "<!-- marker -->", SINCE))
                .contains(new ProductFeedbackIssues.Issue(12, "I_issue", "https://github.com/owner/repo/issues/12"));
        assertRequest("GET", "/app", "app-jwt");
        RecordedRequest first = request();
        assertThat(first.getTarget())
                .isEqualTo(
                        "/repositories/42/issues?creator=hephaestus[bot]&state=all&since=2026-10-30T00:00:00Z&per_page=100");
        assertThat(first.getHeaders().get("Authorization")).isEqualTo("Bearer installation-token");
        assertRequest("GET", "/repositories/42/issues?page=2", "installation-token");
        respond(200, "[]");
        assertThat(adapter.findByMarker(REPOSITORY, "<!-- marker -->", SINCE)).isEmpty();
        assertThat(github.getRequestCount()).isEqualTo(4);
    }

    @Test
    void shouldReturnUnknownWhenSecondMarkerPageFailsEvenAfterMatch() {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        page("[" + ISSUE + "]", "/repositories/42/issues?page=2");
        respond(502, "{}");
        assertThatThrownBy(() -> adapter.findByMarker(REPOSITORY, "<!-- marker -->", SINCE))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class);
    }

    @Test
    void shouldReturnUnknownWhenSecondMarkerPageIsNotFound() {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        page("[]", "/repositories/42/issues?page=2");
        respond(404, "{}");
        assertThatThrownBy(() -> adapter.findByMarker(REPOSITORY, "<!-- marker -->", SINCE))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class);
    }

    @Test
    void shouldReturnUnknownWhenMarkerPageIsMalformed() {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        respond(200, "{\"message\":\"not a page\"}");
        assertThatThrownBy(() -> adapter.findByMarker(REPOSITORY, "<!-- marker -->", SINCE))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class);
    }

    @Test
    void shouldExcludePullRequestsAndCapOpenIssuesAcrossPages() throws Exception {
        installationToken();
        page("""
                [{"number":1,"title":"PR","pull_request":{}},{"number":2,"title":"Issue two"}]
                """, "/repositories/42/issues?page=2");
        respond(200, """
                [{"number":3,"title":"Issue three"},{"number":4,"title":"Issue four"}]
                """);
        assertThat(adapter.openIssues(REPOSITORY, 2))
                .containsExactly(
                        new ProductFeedbackIssues.OpenIssue(2, "Issue two"),
                        new ProductFeedbackIssues.OpenIssue(3, "Issue three"));
        assertRequest(
                "GET",
                "/repositories/42/issues?state=open&sort=updated&direction=desc&per_page=100",
                "installation-token");
        assertRequest("GET", "/repositories/42/issues?page=2", "installation-token");
    }

    @Test
    void shouldReturnNoneWhenPermissionIsNotFound() throws Exception {
        installationToken();
        respond(404, "{}");
        assertThat(adapter.permission(REPOSITORY, "reader")).isEqualTo("none");
        assertRequest("GET", "/repositories/42/collaborators/reader/permission", "installation-token");
    }

    @Test
    void shouldUsePermissionFieldWhenRoleNameDiffers() {
        installationToken();
        respond(200, "{\"permission\":\"read\",\"role_name\":\"maintain\"}");
        assertThat(adapter.permission(REPOSITORY, "reader")).isEqualTo("read");
    }

    @Test
    void shouldResolveRepositoryWithAppJwtAndInstallationToken() throws Exception {
        appJwt();
        installationToken();
        respond(200, "{\"id\":7}");
        respond(200, "{\"id\":42,\"node_id\":\"R_repo\",\"full_name\":\"owner/repo\",\"private\":true}");
        assertThat(adapter.repository("owner/repo")).contains(REPOSITORY);
        assertRequest("GET", "/repos/owner/repo/installation", "app-jwt");
        assertRequest("GET", "/repos/owner/repo", "installation-token");
    }

    @Test
    void shouldReturnEmptyWhenFrozenRepositoryIsNotFound() {
        installationToken();
        respond(404, "{}");
        assertThat(adapter.isPrivate(REPOSITORY)).isEmpty();
    }

    @Test
    void shouldRejectVisibilityWhenRepositoryIdentityDiffers() {
        installationToken();
        respond(200, "{\"id\":99,\"node_id\":\"R_other\",\"private\":false}");
        assertThatThrownBy(() -> adapter.isPrivate(REPOSITORY)).isInstanceOf(ProductFeedbackIssues.Unknown.class);
    }

    @Test
    void shouldOmitNullTypeAndUseFrozenRepositoryOnCreate() throws Exception {
        installationToken();
        respond(201, ISSUE);
        assertThat(adapter.create(REPOSITORY, "title", "body", null).number()).isEqualTo(12);
        RecordedRequest request = request();
        assertThat(request.getTarget()).isEqualTo("/repositories/42/issues");
        var payload = payload(request);
        assertThat(payload.path("title").asString()).isEqualTo("title");
        assertThat(payload.has("type")).isFalse();
    }

    @Test
    void shouldUsePatchForEditsAndClose() throws Exception {
        installationToken();
        respond(200, "{}");
        respond(200, "{}");
        adapter.edit(REPOSITORY, 12, "title", "replacement");
        adapter.close(REPOSITORY, 12, "not_planned");
        assertRequest("PATCH", "/repositories/42/issues/12", "installation-token");
        assertRequest("PATCH", "/repositories/42/issues/12", "installation-token");
    }

    @Test
    void shouldRejectForeignPaginationWithoutSendingToken() {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        github.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Link", "<https://example.invalid/issues?page=2>; rel=\"next\"")
                .body("[]")
                .build());
        assertThatThrownBy(() -> adapter.findByMarker(REPOSITORY, "marker", SINCE))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class);
        assertThat(github.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldReadAllIssueTypePages() throws Exception {
        installationToken();
        respond(200, """
                {"data":{"node":{"owner":{"__typename":"Organization","issueTypes":{
                  "nodes":[{"name":"Bug"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}
                }}}}}
                """);
        respond(200, """
                {"data":{"node":{"owner":{"__typename":"Organization","issueTypes":{
                  "nodes":[{"name":"Feature"}],"pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}}}
                """);
        assertThat(adapter.issueTypes(REPOSITORY)).containsExactly("Bug", "Feature");
        assertRequest("POST", "/graphql", "installation-token");
        var payload = payload(request());
        assertThat(payload.path("variables").path("after").asString()).isEqualTo("next");
    }

    @Test
    void shouldReturnNoIssueTypesForUserOwner() {
        installationToken();
        respond(200, "{\"data\":{\"node\":{\"owner\":{\"__typename\":\"User\"}}}}");
        assertThat(adapter.issueTypes(REPOSITORY)).isEmpty();
    }

    @Test
    void shouldTransferToFrozenNodeId() throws Exception {
        installationToken();
        respond(200, """
                {"data":{"transferIssue":{"issue":{"number":15,"id":"I_issue","url":"https://github.com/owner/repo/issues/15",
                "repository":{"id":"R_repo"}}}}}
                """);
        assertThat(adapter.transfer(
                                new ProductFeedbackIssues.Issue(
                                        12, "I_issue", "https://github.com/owner/public/issues/12"),
                                REPOSITORY)
                        .number())
                .isEqualTo(15);
        var payload = payload(request());
        assertThat(payload.path("variables").path("repository").asString()).isEqualTo("R_repo");
    }

    @Test
    void shouldReadStatesAcrossInstallationsAndEveryCommentPage() throws Exception {
        appJwt();
        installationToken();
        when(tokens.getInstallationToken(8)).thenReturn("other-installation");
        respond(200, "{\"slug\":\"hephaestus\"}");
        respond(200, "[{\"id\":7},{\"id\":8}]");
        respond(200, """
                {"errors":[{"type":"NOT_FOUND","path":["nodes",0]}],"data":{"nodes":[null]}}
                """);
        respond(200, """
                {"data":{"nodes":[{"__typename":"Issue","id":"I_issue","state":"CLOSED","stateReason":"DUPLICATE",
                  "timelineItems":{"nodes":[{"__typename":"MarkedAsDuplicateEvent","canonical":{"id":"I_original"}}]},
                  "comments":{"nodes":[{"fullDatabaseId":"4294967296","author":{"login":"reader"},"body":"/publish",
                    "createdAt":"2026-10-30T00:00:00Z"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}
                }]}}
                """);
        respond(200, """
                {"data":{"node":{"comments":{"nodes":[{"fullDatabaseId":"4294967297","author":{"login":"hephaestus[bot]"},
                "body":"Published","createdAt":"2026-10-30T00:01:00Z"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """);

        var states = adapter.states(List.of("I_issue"));
        assertThat(states).hasSize(1);
        var state = states.getFirst();
        assertThat(state.open()).isFalse();
        assertThat(state.stateReason()).isEqualTo("duplicate");
        assertThat(state.duplicateOfNodeId()).isEqualTo("I_original");
        assertThat(state.comments())
                .extracting(ProductFeedbackIssues.Comment::id)
                .containsExactly(4294967296L, 4294967297L);
        assertThat(state.comments())
                .extracting(ProductFeedbackIssues.Comment::byApp)
                .containsExactly(false, true);
        assertRequest("GET", "/app", "app-jwt");
        assertRequest("GET", "/app/installations?per_page=100", "app-jwt");
        assertRequest("POST", "/graphql", "installation-token");
        assertRequest("POST", "/graphql", "other-installation");
        var payload = payload(request());
        assertThat(payload.path("variables").path("after").asString()).isEqualTo("next");
    }

    @Test
    void shouldBatchAtMostOneHundredStateNodeIdsAndOmitMissingIssues() throws Exception {
        appJwt();
        installationToken();
        respond(200, "{\"slug\":\"hephaestus\"}");
        respond(200, "[{\"id\":7}]");
        respond(200, "{\"data\":{\"nodes\":[" + String.join(",", Collections.nCopies(100, "null")) + "]}}");
        respond(200, "{\"data\":{\"nodes\":[null]}}");
        var ids = IntStream.range(0, 101).mapToObj(index -> "I_" + index).toList();

        assertThat(adapter.states(ids)).isEmpty();
        request();
        request();
        var first = payload(request());
        var second = payload(request());
        assertThat(first.path("variables").path("ids").size()).isEqualTo(100);
        assertThat(second.path("variables").path("ids").size()).isEqualTo(1);
    }

    @Test
    void shouldReturnUnknownWhenGraphQlHasTransientError() {
        installationToken();
        respond(200, "{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"private text\"}]}");
        assertThatThrownBy(() -> adapter.transfer(new ProductFeedbackIssues.Issue(1, "I_issue", "url"), REPOSITORY))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class)
                .hasMessageNotContaining("private text");
    }

    @Test
    void shouldReturnUnknownWhenTokenMintingWasRateLimited() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "60");
        var rateLimit =
                WebClientResponseException.create(403, "Forbidden", headers, new byte[0], StandardCharsets.UTF_8);
        when(tokens.getInstallationToken(7)).thenThrow(new InstallationSuspendedException(7, rateLimit));

        assertThatThrownBy(() -> adapter.create(REPOSITORY, "title", "body", null))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class)
                .hasMessageContaining("Retry at");
        assertThat(github.getRequestCount()).isZero();
    }

    @Test
    void shouldReturnUnknownWithoutRetryWhenWriteResponseIsDropped() {
        installationToken();
        github.enqueue(new MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body(ISSUE)
                .onResponseBody(new SocketEffect.CloseSocket())
                .build());

        assertThatThrownBy(() -> adapter.create(REPOSITORY, "title", "body", null))
                .isInstanceOf(ProductFeedbackIssues.Unknown.class);
        assertThat(github.getRequestCount()).isEqualTo(1);
    }

    private void installationToken() {
        when(tokens.getInstallationToken(7)).thenReturn("installation-token");
    }

    private void appJwt() {
        when(tokens.generateAppJWT()).thenReturn("app-jwt");
    }

    private void respond(int status, String body) {
        github.enqueue(new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private void page(String body, String next) {
        github.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Link", "<" + github.url(next) + ">; rel=\"next\", <" + github.url(next) + ">; rel=\"last\"")
                .body(body)
                .build());
    }

    private static JsonNode payload(RecordedRequest request) {
        var body = request.getBody();
        if (body == null) throw new AssertionError("Expected a GitHub request body");
        return JsonMapper.builder().build().readTree(body.utf8());
    }

    private RecordedRequest request() throws InterruptedException {
        RecordedRequest request = github.takeRequest(5, TimeUnit.SECONDS);
        if (request == null) throw new AssertionError("Expected a GitHub request");
        return request;
    }

    private void assertRequest(String method, String target, String token) throws InterruptedException {
        RecordedRequest request = request();
        assertThat(request.getMethod()).isEqualTo(method);
        assertThat(request.getTarget()).isEqualTo(target);
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + token);
    }
}
