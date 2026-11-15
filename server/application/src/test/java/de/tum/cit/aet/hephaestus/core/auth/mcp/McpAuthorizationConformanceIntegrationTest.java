package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.SessionRevocation;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.Pkce;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Conformance of the MCP authorization server, one test per rule, written from the specifications: OAuth
 * 2.1 (draft-ietf-oauth-v2-1), RFC 8707, RFC 9207, RFC 8252 § 7.3, the client ID metadata document draft,
 * and the ADR 0048 refresh rotation that the MCP client session inherits. Each test asserts on the rows it
 * created and on what crosses HTTP.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class McpAuthorizationConformanceIntegrationTest extends RealAuthIntegrationTest {

    private static final String LIST_TOOLS = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private SessionRevocation sessionRevocation;

    @Autowired
    private McpAuthorizationService authorization;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private McpAuthorizationFlow flow;
    private String cookie;
    private long accountId;
    private String slug;

    @BeforeEach
    void signInAsAWorkspaceAdmin() {
        flow = new McpAuthorizationFlow(webTestClient, cookieName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cookie = flow.signIn("mcp-admin-" + suffix);
        accountId = McpAuthorizationFlow.accountId(cookie);
        slug = "mcp-" + suffix;
        join(workspace(slug), accountId, Membership.WorkspaceRole.ADMIN);
    }

    @Test
    void shouldRequirePkceS256AndRefusePlain() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        Map<String, String> plain = flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        plain.put("code_challenge_method", "plain");
        plain.put("code_challenge", pkce.verifier());
        Map<String, String> missing = flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        missing.remove("code_challenge");

        for (Map<String, String> parameters : List.of(plain, missing)) {
            Map<String, String> answer = McpAuthorizationFlow.query(flow.authorize(cookie, parameters));
            assertThat(answer).containsEntry("error", "invalid_request").doesNotContainKey("code");
        }
        assertThat(grantsOf(accountId)).isZero();
    }

    @Test
    void shouldAcceptACodeOnceAndRefuseItWhenExpired() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        String code = approvedCode(pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        Map<String, String> form = flow.codeForm(code, pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT);

        flow.tokens(flow.token(form));
        assertThat(flow.tokenError(flow.token(form))).isEqualTo("invalid_grant");

        McpAuthorizationFlow.Pkce second = McpAuthorizationFlow.newPkce();
        String expired = approvedCode(second, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        jdbc.update("UPDATE client_sign_in_handoff SET expires_at = now() - interval '1 second' WHERE mcp_client_grant_id IS NOT NULL AND consumed_at IS NULL AND account_id = ?", accountId);
        assertThat(flow.tokenError(flow.token(flow.codeForm(expired, second, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT))))
                .isEqualTo("invalid_grant");
    }

    @Test
    void shouldBurnTheCodeWhenTheVerifierDoesNotMatch() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        String code = approvedCode(pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        McpAuthorizationFlow.Pkce wrong = McpAuthorizationFlow.newPkce();

        assertThat(flow.tokenError(flow.token(flow.codeForm(code, wrong, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT))))
                .isEqualTo("invalid_grant");
        assertThat(flow.tokenError(flow.token(flow.codeForm(code, pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT))))
                .isEqualTo("invalid_grant");
    }

    @Test
    void shouldMatchTheRedirectExactlyExceptTheLoopbackPort() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();

        URI loopbackWithPort = flow.authorize(cookie, flow.parameters(slug, pkce, "http://127.0.0.1:53111/callback"));
        URI listed = flow.authorize(cookie, flow.parameters(slug, pkce, McpAuthorizationFlow.WEB_REDIRECT));
        URI otherPath = flow.authorize(cookie, flow.parameters(slug, pkce, "http://127.0.0.1:53111/elsewhere"));
        URI extended = flow.authorize(cookie, flow.parameters(slug, pkce, McpAuthorizationFlow.WEB_REDIRECT + "/more"));
        URI otherPort = flow.authorize(cookie, flow.parameters(slug, pkce, "https://client.example:8443/callback"));

        McpAuthorizationFlow.consentRequest(loopbackWithPort);
        McpAuthorizationFlow.consentRequest(listed);
        for (URI refused : List.of(otherPath, extended, otherPort)) {
            // An untrusted redirect ends on the error page and is never redirected to (RFC 9700 § 4.11).
            assertThat(refused.toString())
                    .isEqualTo(McpAuthorizationFlow.ISSUER + "/mcp/authorize?error=INVALID_REDIRECT_URI");
        }
    }

    @Test
    void shouldBindTheResourceIntoTheTokenAudience() {
        McpAuthorizationFlow.Tokens tokens = flow.connect(cookie, slug);

        var payload = McpAuthorizationFlow.payload(tokens.accessToken());
        assertThat(payload.path("aud").isArray() ? payload.path("aud").get(0).asString() : payload.path("aud").asString())
                .isEqualTo(McpAuthorizationFlow.resource(slug));
        assertThat(payload.path("aud").isArray() ? payload.path("aud").size() : 1).isEqualTo(1);
        assertThat(payload.has("roles")).isFalse();
        assertThat(payload.path("scope").asString()).isEqualTo("practices:read");
        assertThat(tokens.scope()).isEqualTo("practices:read");

        flow.callTool(tokens.accessToken(), slug, LIST_TOOLS).expectStatus().isOk().expectBody(Void.class);
        webTestClient.get()
                .uri("/user")
                .headers(h -> h.setBearerAuth(tokens.accessToken()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseACodeExchangedForAnotherResource() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        String code = approvedCode(pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        String otherSlug = slug + "-b";
        join(workspace(otherSlug), accountId, Membership.WorkspaceRole.ADMIN);
        Map<String, String> other = flow.codeForm(code, pkce, otherSlug, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        Map<String, String> foreign = flow.codeForm(code, pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        foreign.put("resource", "https://elsewhere.example/mcp");

        assertThat(flow.tokenError(flow.token(other))).isEqualTo("invalid_target");
        assertThat(flow.tokenError(flow.token(foreign))).isEqualTo("invalid_target");
        flow.tokens(flow.token(flow.codeForm(code, pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT)));
    }

    @Test
    void shouldNameTheIssuerOnEveryAuthorizationResponse() {
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        Map<String, String> badType = flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        badType.put("response_type", "token");

        URI success = flow.approve(cookie, McpAuthorizationFlow.consentRequest(
                flow.authorize(cookie, flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT))));
        URI refusal = flow.refuse(cookie, McpAuthorizationFlow.consentRequest(
                flow.authorize(cookie, flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT))));
        URI error = flow.authorize(cookie, badType);

        assertThat(McpAuthorizationFlow.query(success)).containsEntry("iss", McpAuthorizationFlow.ISSUER).containsKey("code");
        assertThat(McpAuthorizationFlow.query(refusal))
                .containsEntry("iss", McpAuthorizationFlow.ISSUER)
                .containsEntry("error", "access_denied");
        assertThat(McpAuthorizationFlow.query(error))
                .containsEntry("iss", McpAuthorizationFlow.ISSUER)
                .containsEntry("error", "unsupported_response_type");
    }

    @Test
    void shouldRotateRefreshTokensAndEndTheSessionWhenAnOldOneReturns() {
        McpAuthorizationFlow.Tokens first = flow.connect(cookie, slug);
        McpAuthorizationFlow.Tokens second = flow.tokens(flow.token(flow.refreshForm(first.refreshToken())));
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        flow.callTool(second.accessToken(), slug, LIST_TOOLS).expectStatus().isOk().expectBody(Void.class);

        assertThat(flow.tokenError(flow.token(flow.refreshForm(first.refreshToken())))).isEqualTo("invalid_grant");

        // The reuse ended the whole session: the current refresh secret and access token die with it.
        assertThat(flow.tokenError(flow.token(flow.refreshForm(second.refreshToken())))).isEqualTo("invalid_grant");
        flow.callTool(second.accessToken(), slug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(grantStatus(sidOf(second))).isEqualTo("REVOKED");
    }

    @Test
    void shouldRefreshAnMcpSessionOnlyAtItsOwnTokenEndpoint() {
        McpAuthorizationFlow.Tokens tokens = flow.connect(cookie, slug);
        Map<String, String> otherClient = flow.refreshForm(tokens.refreshToken());
        otherClient.put("client_id", "https://other.example/client.json");

        webTestClient.post()
                .uri("/auth/client/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"" + tokens.refreshToken() + "\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(flow.tokenError(flow.token(otherClient))).isEqualTo("invalid_grant");
        flow.tokens(flow.token(flow.refreshForm(tokens.refreshToken())));
    }

    @Test
    void shouldEndCodesAndTokensWhenTheAccountSignsOutEverywhere() {
        McpAuthorizationFlow.Tokens tokens = flow.connect(cookie, slug);
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        String code = approvedCode(pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);

        sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, null);

        assertThat(flow.tokenError(flow.token(flow.codeForm(code, pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT))))
                .isEqualTo("invalid_grant");
        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken())))).isEqualTo("invalid_grant");
        flow.callTool(tokens.accessToken(), slug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(openGrantsOf(accountId)).isZero();
    }

    /** Round 3, race 1: an approval whose cookie the decoder accepted before "sign out everywhere" committed. */
    @Test
    void shouldWriteNoCodeWhenAnApprovalRacesSignOutEverywhere() {
        UUID requestId = McpAuthorizationFlow.consentRequest(flow.authorize(
                cookie, flow.parameters(slug, McpAuthorizationFlow.newPkce(), McpAuthorizationFlow.LOOPBACK_REDIRECT)));
        UUID approvingJti = UUID.fromString(McpAuthorizationFlow.payload(cookie).path("jti").asString());

        sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, null);
        var answer = authorization.approve(requestId, accountId, approvingJti, Instant.now().plusSeconds(3600), Instant.now());

        assertThat(answer).isInstanceOf(McpAuthorizationService.Approval.Gone.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM client_sign_in_handoff WHERE mcp_client_grant_id = ?", Long.class, requestId))
                .isZero();
    }

    /** Round 3, race 2: a console revoke and the code's exchange, in both orders. */
    @Test
    void shouldNeverReactivateARevokedGrantWhenItsExchangeRacesAConsoleRevoke() {
        long workspaceId = workspaceRepository.findByWorkspaceSlug(slug).orElseThrow().getId();
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        URI callback = flow.approve(cookie, McpAuthorizationFlow.consentRequest(
                flow.authorize(cookie, flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT))));
        UUID revokedFirst = grantOfCode(McpAuthorizationFlow.code(callback));

        assertThat(authorization.revoke(workspaceId, revokedFirst, accountId)).isTrue();
        assertThat(flow.tokenError(flow.token(
                        flow.codeForm(McpAuthorizationFlow.code(callback), pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT))))
                .isEqualTo("invalid_grant");
        assertThat(grantStatusById(revokedFirst)).isEqualTo("REVOKED");

        McpAuthorizationFlow.Tokens exchangedFirst = flow.connect(cookie, slug);
        UUID linked = grantIdOfSession(sidOf(exchangedFirst));
        assertThat(authorization.revoke(workspaceId, linked, accountId)).isTrue();
        assertThat(grantStatusById(linked)).isEqualTo("REVOKED");
        flow.callTool(exchangedFirst.accessToken(), slug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(flow.tokenError(flow.token(flow.refreshForm(exchangedFirst.refreshToken())))).isEqualTo("invalid_grant");
    }

    @Test
    void shouldRefuseConsentToAMemberWhoIsNotAnAdmin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String memberCookie = flow.signIn("mcp-member-" + suffix);
        join(workspaceRepository.findByWorkspaceSlug(slug).orElseThrow(), McpAuthorizationFlow.accountId(memberCookie), Membership.WorkspaceRole.MEMBER);
        UUID requestId = McpAuthorizationFlow.consentRequest(flow.authorize(
                memberCookie, flow.parameters(slug, McpAuthorizationFlow.newPkce(), McpAuthorizationFlow.LOOPBACK_REDIRECT)));

        webTestClient.get()
                .uri("/mcp/oauth/requests/{id}", requestId)
                .header(HttpHeaders.COOKIE, cookieName + "=" + memberCookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.refusal")
                .isEqualTo("NOT_ADMIN");
        flow.approval(memberCookie, requestId).expectStatus().isEqualTo(409).expectBody(Void.class);
    }

    @Test
    void shouldAnswerInvalidTargetForAWorkspaceWithoutMembership() {
        String otherSlug = slug + "-x";
        workspace(otherSlug);
        Map<String, String> answer = McpAuthorizationFlow.query(flow.authorize(
                cookie, flow.parameters(otherSlug, McpAuthorizationFlow.newPkce(), McpAuthorizationFlow.LOOPBACK_REDIRECT)));
        Map<String, String> unknown = McpAuthorizationFlow.query(flow.authorize(
                cookie, flow.parameters("no-such-" + slug, McpAuthorizationFlow.newPkce(), McpAuthorizationFlow.LOOPBACK_REDIRECT)));

        assertThat(answer).containsEntry("error", "invalid_target");
        assertThat(unknown).containsEntry("error", "invalid_target");
    }

    @Test
    void shouldSendASignedOutBrowserToTheWebAppToSignIn() {
        URI location = flow.authorize(
                null, flow.parameters(slug, McpAuthorizationFlow.newPkce(), McpAuthorizationFlow.LOOPBACK_REDIRECT));

        assertThat(location.toString()).startsWith(McpAuthorizationFlow.ISSUER + "/mcp/authorize?response_type=code");
        assertThat(grantsOf(accountId)).isZero();
    }

    @Test
    void shouldServeMetadataThatPointsAClientToThisIssuer() {
        webTestClient.get()
                .uri("/.well-known/oauth-protected-resource/api/workspaces/{slug}/mcp", slug)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.resource")
                .isEqualTo(McpAuthorizationFlow.resource(slug))
                .jsonPath("$.authorization_servers[0]")
                .isEqualTo(McpAuthorizationFlow.ISSUER);
        webTestClient.get()
                .uri("/.well-known/oauth-authorization-server")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.issuer")
                .isEqualTo(McpAuthorizationFlow.ISSUER)
                .jsonPath("$.code_challenge_methods_supported[0]")
                .isEqualTo("S256")
                .jsonPath("$.client_id_metadata_document_supported")
                .isEqualTo(true)
                .jsonPath("$.authorization_response_iss_parameter_supported")
                .isEqualTo(true);
        webTestClient.post()
                .uri("/workspaces/{slug}/mcp", slug)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer resource_metadata=\"" + McpAuthorizationFlow.ISSUER
                                + "/.well-known/oauth-protected-resource/api/workspaces/" + slug + "/mcp\"")
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAWebAppTokenAtTheMcpEndpoint() {
        flow.callTool(cookie, slug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    @Test
    void shouldRefuseAnMcpTokenForAnotherWorkspace() {
        McpAuthorizationFlow.Tokens tokens = flow.connect(cookie, slug);
        String otherSlug = slug + "-o";
        join(workspace(otherSlug), accountId, Membership.WorkspaceRole.ADMIN);

        flow.callTool(tokens.accessToken(), otherSlug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    /** Round 1, P1-3: a token approved for one workspace never reaches the workspace that takes its old slug. */
    @Test
    void shouldRefuseAnMcpTokenAfterItsSlugPassesToAnotherWorkspace() {
        McpAuthorizationFlow.Tokens tokens = flow.connect(cookie, slug);
        Workspace original = workspaceRepository.findByWorkspaceSlug(slug).orElseThrow();
        original.setWorkspaceSlug(slug + "-renamed");
        workspaceRepository.save(original);
        join(workspace(slug), accountId, Membership.WorkspaceRole.ADMIN);

        flow.callTool(tokens.accessToken(), slug, LIST_TOOLS).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    private String approvedCode(McpAuthorizationFlow.Pkce pkce, String redirectUri) {
        URI callback = flow.approve(
                cookie, McpAuthorizationFlow.consentRequest(flow.authorize(cookie, flow.parameters(slug, pkce, redirectUri))));
        return McpAuthorizationFlow.code(callback);
    }

    private Workspace workspace(String workspaceSlug) {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(workspaceSlug);
        workspace.setDisplayName("MCP " + workspaceSlug);
        workspace.setAccountLogin(workspaceSlug + "-org");
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        return workspaceRepository.save(workspace);
    }

    private void join(Workspace workspace, long account, Membership.WorkspaceRole role) {
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), account));
        membership.setRole(role);
        membershipRepository.save(membership);
    }

    private static UUID sidOf(McpAuthorizationFlow.Tokens tokens) {
        return UUID.fromString(McpAuthorizationFlow.payload(tokens.accessToken()).path("sid").asString());
    }

    private long grantsOf(long account) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM mcp_client_grant WHERE account_id = ?", Long.class, account));
    }

    private long openGrantsOf(long account) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM mcp_client_grant WHERE account_id = ? AND status IN ('PENDING', 'APPROVED', 'ACTIVE')",
                Long.class,
                account));
    }

    private String grantStatus(UUID sessionId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT status FROM mcp_client_grant WHERE client_session_id = ?", String.class, sessionId));
    }

    private String grantStatusById(UUID grantId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM mcp_client_grant WHERE id = ?", String.class, grantId));
    }

    private UUID grantIdOfSession(UUID sessionId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM mcp_client_grant WHERE client_session_id = ?", UUID.class, sessionId));
    }

    private UUID grantOfCode(String code) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT mcp_client_grant_id FROM client_sign_in_handoff WHERE code_hash = ?",
                UUID.class,
                Pkce.hash(code)));
    }
}
