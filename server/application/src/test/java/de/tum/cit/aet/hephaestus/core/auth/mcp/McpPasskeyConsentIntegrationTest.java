package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionPruner;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consent in a workspace that requires an admin passkey (ADR 0110 decision 8, update of 2026-11-17): the account
 * confirms with a passkey on the consent page, the grant records that proof, and its calls satisfy the policy only
 * within the grant's bound.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class McpPasskeyConsentIntegrationTest extends RealAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "credential-of-the-approving-passkey";
    private static final Duration IDLE_LAPSE = McpClientGrant.IDLE_LAPSE;

    private static final String GET_REVIEW_SETTINGS = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "get_review_settings", "arguments": {}}}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private McpClientGrantCleanupJob cleanupJob;

    @Autowired
    private ClientSessionPruner sessionPruner;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private McpAuthorizationFlow flow;
    private String cookie;
    private long accountId;
    private Workspace workspace;

    @BeforeEach
    void signInAsAnAdminOfAPasskeyWorkspace() {
        flow = new McpAuthorizationFlow(webTestClient, cookieName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cookie = flow.signIn("mcp-passkey-" + suffix);
        accountId = McpAuthorizationFlow.accountId(cookie);
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mcp-passkey-" + suffix);
        workspace.setDisplayName("MCP passkey");
        workspace.setAccountLogin("mcp-passkey-org-" + suffix);
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace.setAdminPasskeyRequired(true);
        workspace = workspaceRepository.save(workspace);
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountId));
        membership.setRole(Membership.WorkspaceRole.ADMIN);
        membershipRepository.save(membership);
    }

    @Test
    void shouldAskForAPasskeyAndRefuseAnApprovalWithoutAFreshProof() {
        UUID requestId = request();

        consent(cookie, requestId)
                .jsonPath("$.passkeyRequired")
                .isEqualTo(true)
                .jsonPath("$.refusal")
                .doesNotExist();
        flow.approval(cookie, requestId).expectStatus().isEqualTo(409).expectBody(Void.class);
        flow.approval(passkeyCookie(Instant.now().minus(Duration.ofMinutes(10))), requestId)
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        // A fresh proof that names no credential, as a session from before the credential claim carries.
        Instant now = Instant.now();
        String unnamed = jwtIssuer
                .issue(accountId, TokenConstraints.browserSession(now.plus(Duration.ofHours(8)), now, now, null), null)
                .value();
        flow.approval(unnamed, requestId).expectStatus().isEqualTo(409).expectBody(Void.class);

        assertThat(grantColumn(requestId, "status")).isEqualTo("PENDING");
        assertThat(grantColumn(requestId, "passkey_credential_id")).isNull();
    }

    /** A passkey-backed grant outlives the web session that approved it: it lapses only when unused. */
    @Test
    void shouldRecordThePasskeyAndKeepTheGrantBeyondTheApprovingWebSession() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String shortSession = jwtIssuer
                .issue(
                        accountId,
                        TokenConstraints.browserSession(now.plus(Duration.ofMinutes(20)), now, now, CREDENTIAL),
                        null)
                .value();
        Instant before = Instant.now();
        McpAuthorizationFlow.Tokens tokens = connect(shortSession);
        Instant after = Instant.now();
        UUID grant = grantOf(tokens);

        assertThat(grantColumn(grant, "passkey_credential_id")).isEqualTo(CREDENTIAL);
        assertThat(jdbc.queryForObject(
                        "SELECT passkey_verified_at FROM mcp_client_grant WHERE id = ?", Timestamp.class, grant))
                .isEqualTo(Timestamp.from(now));
        assertThat(sessionDeadline(grant))
                .isBetween(
                        before.plus(IDLE_LAPSE).minusSeconds(1),
                        after.plus(IDLE_LAPSE).plusSeconds(1));
        assertThat(grantColumn(grant, "last_used_at")).isNotNull();

        JsonNode settings = structured(call(tokens.accessToken(), GET_REVIEW_SETTINGS));
        structured(call(
                tokens.accessToken(),
                tool(
                        "set_feedback_delivery",
                        Map.of("etag", settings.path("etag").asString(), "deliveryStatus", "PAUSED"))));

        // The audit row of the write names the grant, and the grant says that a passkey confirmed it.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event e JOIN mcp_client_grant g ON g.id = e.mcp_client_grant_id"
                                + " WHERE g.id = ? AND g.passkey_verified_at IS NOT NULL",
                        Long.class,
                        grant))
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT details::text FROM auth_event WHERE event_type = 'MCP_CLIENT_APPROVED' AND account_id = ?",
                        String.class,
                        accountId))
                .contains("\"passkey\":true");
    }

    /** Each refresh moves the idle deadline; a call records its use at most once an hour. */
    @Test
    void shouldMoveTheIdleDeadlineOnRefreshAndRecordTheLastUse() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        UUID grant = grantOf(tokens);
        Instant longAgo = Instant.now().minus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update(
                "UPDATE client_session SET session_expires_at = ? WHERE id = (SELECT client_session_id FROM"
                        + " mcp_client_grant WHERE id = ?)",
                Timestamp.from(Instant.now().plus(Duration.ofDays(10))),
                grant);
        jdbc.update("UPDATE mcp_client_grant SET last_used_at = ? WHERE id = ?", Timestamp.from(longAgo), grant);

        structured(call(tokens.accessToken(), GET_REVIEW_SETTINGS));
        assertThat(jdbc.queryForObject(
                        "SELECT last_used_at FROM mcp_client_grant WHERE id = ?", Timestamp.class, grant))
                .isAfter(Timestamp.from(longAgo));

        Instant before = Instant.now();
        flow.tokens(flow.token(flow.refreshForm(tokens.refreshToken())));
        assertThat(sessionDeadline(grant))
                .isAfterOrEqualTo(before.plus(IDLE_LAPSE).minusSeconds(1));
    }

    /** A grant unused for 90 days lapses: the hourly cleanup ends it, the console drops it, and its tokens stop. */
    @Test
    void shouldLapseAGrantThatWentUnusedForNinetyDays() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        UUID grant = grantOf(tokens);
        jdbc.update(
                "UPDATE client_session SET session_expires_at = ? WHERE id = (SELECT client_session_id FROM"
                        + " mcp_client_grant WHERE id = ?)",
                Timestamp.from(Instant.now().minusSeconds(60)),
                grant);

        // The session pruner runs first in the hour and deletes the lapsed session row before the grant cleanup.
        sessionPruner.prune();
        cleanupJob.cleanup();

        assertThat(grantColumn(grant, "status")).isEqualTo("EXPIRED");
        flow.callTool(tokens.accessToken(), workspace.getWorkspaceSlug(), GET_REVIEW_SETTINGS)
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken()))))
                .isEqualTo("invalid_grant");
    }

    /** One lifetime rule for every grant: one approved without a passkey also outlives its web session and lapses. */
    @Test
    void shouldGiveAGrantWithoutPasskeyTheSameLifetime() {
        workspace.setAdminPasskeyRequired(false);
        workspaceRepository.save(workspace);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String shortSession = jwtIssuer
                .issue(
                        accountId,
                        TokenConstraints.browserSession(now.plus(Duration.ofMinutes(20)), now, null, null),
                        null)
                .value();
        Instant before = Instant.now();
        McpAuthorizationFlow.Tokens tokens = connect(shortSession);
        Instant after = Instant.now();
        UUID grant = grantOf(tokens);

        assertThat(grantColumn(grant, "passkey_verified_at")).isNull();
        assertThat(sessionDeadline(grant))
                .isBetween(
                        before.plus(IDLE_LAPSE).minusSeconds(1),
                        after.plus(IDLE_LAPSE).plusSeconds(1));
        structured(call(tokens.accessToken(), GET_REVIEW_SETTINGS));

        jdbc.update(
                "UPDATE client_session SET session_expires_at = ? WHERE id = (SELECT client_session_id FROM"
                        + " mcp_client_grant WHERE id = ?)",
                Timestamp.from(Instant.now().minusSeconds(60)),
                grant);
        // The session pruner runs first in the hour and deletes the lapsed session row before the grant cleanup.
        sessionPruner.prune();
        cleanupJob.cleanup();

        assertThat(grantColumn(grant, "status")).isEqualTo("EXPIRED");
        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken()))))
                .isEqualTo("invalid_grant");
    }

    /** A refresh secret presented twice means two holders: the whole grant ends, and the record says why. */
    @Test
    void shouldRevokeTheGrantAndRecordWhyWhenARefreshSecretIsReused() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        UUID grant = grantOf(tokens);
        McpAuthorizationFlow.Tokens rotated = flow.tokens(flow.token(flow.refreshForm(tokens.refreshToken())));

        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken()))))
                .isEqualTo("invalid_grant");

        assertThat(grantColumn(grant, "status")).isEqualTo("REVOKED");
        assertThat(flow.tokenError(flow.token(flow.refreshForm(rotated.refreshToken()))))
                .isEqualTo("invalid_grant");
        flow.callTool(rotated.accessToken(), workspace.getWorkspaceSlug(), GET_REVIEW_SETTINGS)
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_event WHERE event_type = 'JWT_REVOKED'"
                                + " AND failure_reason = 'client_refresh_reuse' AND workspace_id = ?"
                                + " AND details LIKE ?",
                        Long.class,
                        workspace.getId(),
                        "%" + grant + "%"))
                .isEqualTo(1);
    }

    @Test
    void shouldRefuseTheCallsOfAGrantApprovedBeforeThePolicyTurnedOn() {
        workspace.setAdminPasskeyRequired(false);
        workspaceRepository.save(workspace);
        McpAuthorizationFlow.Tokens tokens = connect(cookie);
        assertThat(grantColumn(grantOf(tokens), "passkey_verified_at")).isNull();

        workspace.setAdminPasskeyRequired(true);
        workspaceRepository.save(workspace);

        JsonNode refused = call(tokens.accessToken(), GET_REVIEW_SETTINGS);
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("passkey");
        JsonNode write = call(
                tokens.accessToken(),
                tool("set_feedback_delivery", Map.of("etag", "\"any\"", "deliveryStatus", "PAUSED")));
        assertThat(write.path("isError").asBoolean()).isTrue();

        // A refresh would move its idle deadline, so it ends the grant instead, with the reason.
        UUID grant = grantOf(tokens);
        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken()))))
                .isEqualTo("invalid_grant");
        assertThat(grantColumn(grant, "status")).isEqualTo("REVOKED");
        assertThat(revokedReasonOf(grant)).isEqualTo("passkey_required");
    }

    /** A removed or demoted admin's client cannot keep its grant alive by refreshing it. */
    @Test
    void shouldEndTheGrantOnRefreshWhenTheAccountIsNoLongerAnAdmin() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        UUID grant = grantOf(tokens);
        Membership membership = membershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId)
                .orElseThrow();
        membership.setRole(Membership.WorkspaceRole.MEMBER);
        membershipRepository.save(membership);

        assertThat(flow.tokenError(flow.token(flow.refreshForm(tokens.refreshToken()))))
                .isEqualTo("invalid_grant");
        assertThat(grantColumn(grant, "status")).isEqualTo("REVOKED");
        assertThat(revokedReasonOf(grant)).isEqualTo("not_admin");
        flow.callTool(tokens.accessToken(), workspace.getWorkspaceSlug(), GET_REVIEW_SETTINGS)
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldEndAPasskeyBackedGrantWhenAnAdminRevokesIt() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        UUID grant = grantOf(tokens);
        String admin = passkeyCookie(Instant.now());

        webTestClient
                .get()
                .uri("/workspaces/{slug}/mcp-grants", workspace.getWorkspaceSlug())
                .header(HttpHeaders.COOKIE, cookieName + "=" + admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '%s')].passkeyBacked".formatted(grant))
                .isEqualTo(true)
                .jsonPath("$[?(@.id == '%s')].lastUsedAt".formatted(grant))
                .isNotEmpty()
                .jsonPath("$[?(@.id == '%s')].expiresAt".formatted(grant))
                .isNotEmpty();
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        webTestClient
                .delete()
                .uri("/workspaces/{slug}/mcp-grants/{id}", workspace.getWorkspaceSlug(), grant)
                .header(HttpHeaders.COOKIE, cookieName + "=" + admin, "__Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .exchange()
                .expectStatus()
                .is2xxSuccessful()
                .expectBody(Void.class);

        assertThat(grantColumn(grant, "status")).isEqualTo("REVOKED");
        flow.callTool(tokens.accessToken(), workspace.getWorkspaceSlug(), GET_REVIEW_SETTINGS)
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseConsentWhileThePasskeyRecoveryIsPending() {
        var account = accountRepository.findById(accountId).orElseThrow();
        account.setPasskeyRecoveryRequired(true);
        accountRepository.save(account);
        UUID requestId = request();

        consent(cookie, requestId)
                .jsonPath("$.refusal")
                .isEqualTo("PASSKEY_RECOVERY_PENDING")
                .jsonPath("$.passkeyRequired")
                .isEqualTo(false);
        flow.approval(passkeyCookie(Instant.now()), requestId)
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(grantColumn(requestId, "status")).isEqualTo("PENDING");
    }

    /** A browser token whose passkey proof is of this time and names {@link #CREDENTIAL}, as a step-up leaves it. */
    private String passkeyCookie(Instant proof) {
        Instant now = Instant.now();
        return jwtIssuer
                .issue(
                        accountId,
                        TokenConstraints.browserSession(now.plus(Duration.ofHours(8)), now, proof, CREDENTIAL),
                        null)
                .value();
    }

    private UUID request() {
        return McpAuthorizationFlow.consentRequest(flow.authorize(
                cookie,
                flow.parameters(
                        workspace.getWorkspaceSlug(),
                        McpAuthorizationFlow.newPkce(),
                        McpAuthorizationFlow.LOOPBACK_REDIRECT)));
    }

    private WebTestClient.BodyContentSpec consent(String browser, UUID requestId) {
        return webTestClient
                .get()
                .uri("/mcp/oauth/requests/{id}", requestId)
                .header(HttpHeaders.COOKIE, cookieName + "=" + browser)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private McpAuthorizationFlow.Tokens connect(String browser) {
        String slug = workspace.getWorkspaceSlug();
        McpAuthorizationFlow.Pkce pkce = McpAuthorizationFlow.newPkce();
        Map<String, String> parameters = flow.parameters(slug, pkce, McpAuthorizationFlow.LOOPBACK_REDIRECT);
        parameters.put("scope", "practices:write");
        URI callback = flow.approve(browser, McpAuthorizationFlow.consentRequest(flow.authorize(browser, parameters)));
        return flow.tokens(flow.token(flow.codeForm(
                McpAuthorizationFlow.code(callback), pkce, slug, McpAuthorizationFlow.LOOPBACK_REDIRECT)));
    }

    private JsonNode call(String accessToken, String body) {
        String answer = flow.callTool(accessToken, workspace.getWorkspaceSlug(), body)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return JSON.readTree(Objects.requireNonNull(answer)).path("result");
    }

    private static String tool(String name, Map<String, Object> arguments) {
        return JSON.writeValueAsString(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "tools/call",
                "params",
                Map.of("name", name, "arguments", arguments)));
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
                .as("tool error: %s", text(result))
                .isFalse();
        return result.path("structuredContent");
    }

    private static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asString("");
    }

    /** The reason that the grant's revocation event records. */
    private String revokedReasonOf(UUID grant) {
        String details = jdbc.queryForObject(
                "SELECT details FROM auth_event WHERE event_type = 'MCP_CLIENT_REVOKED' AND details LIKE ?",
                String.class,
                "%" + grant + "%");
        return JSON.readTree(Objects.requireNonNull(details)).path("reason").asString();
    }

    private Instant sessionDeadline(UUID grant) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT s.session_expires_at FROM client_session s JOIN mcp_client_grant g"
                                + " ON g.client_session_id = s.id WHERE g.id = ?",
                        Timestamp.class,
                        grant))
                .toInstant();
    }

    private UUID grantOf(McpAuthorizationFlow.Tokens tokens) {
        String session =
                McpAuthorizationFlow.payload(tokens.accessToken()).path("sid").asString();
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM mcp_client_grant WHERE client_session_id = ?", UUID.class, UUID.fromString(session)));
    }

    private @Nullable String grantColumn(UUID grant, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM mcp_client_grant WHERE id = ?", String.class, grant);
    }
}
