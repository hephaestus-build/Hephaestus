package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

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
    /** The bound with the default admin proof window of 6 hours. */
    private static final Duration PASSKEY_BOUND = Duration.ofHours(1);

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

    @Test
    void shouldRecordThePasskeyOnTheGrantAndAcceptItsWritesWithinTheBound() {
        Instant proof = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(proof));
        UUID grant = grantOf(tokens);

        assertThat(grantColumn(grant, "passkey_credential_id")).isEqualTo(CREDENTIAL);
        assertThat(jdbc.queryForObject(
                        "SELECT passkey_verified_at FROM mcp_client_grant WHERE id = ?", Timestamp.class, grant))
                .isEqualTo(Timestamp.from(proof));
        // The client session, and with it every refresh, ends at the bound.
        assertThat(jdbc.queryForObject(
                        "SELECT s.session_expires_at FROM client_session s JOIN mcp_client_grant g"
                                + " ON g.client_session_id = s.id WHERE g.id = ?",
                        Timestamp.class,
                        grant))
                .isEqualTo(Timestamp.from(proof.plus(PASSKEY_BOUND)));

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

    @Test
    void shouldRefuseACallOnceTheGrantsPasskeyBoundHasPassed() {
        McpAuthorizationFlow.Tokens tokens = connect(passkeyCookie(Instant.now()));
        structured(call(tokens.accessToken(), GET_REVIEW_SETTINGS));

        jdbc.update(
                "UPDATE mcp_client_grant SET passkey_verified_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(PASSKEY_BOUND).minusSeconds(1)),
                grantOf(tokens));

        JsonNode refused = call(tokens.accessToken(), GET_REVIEW_SETTINGS);
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("passkey");
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
                .jsonPath("$[?(@.id == '%s')].passkeyBackedUntil".formatted(grant))
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

    /** The console reports the access end that approval enforced, not a later one. */
    @Test
    void shouldReportTheSessionDeadlineWhenTheApprovingSessionEndsBeforeTheBound() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant sessionEnd = now.plus(Duration.ofMinutes(20));
        String shortSession = jwtIssuer
                .issue(accountId, TokenConstraints.browserSession(sessionEnd, now, now, CREDENTIAL), null)
                .value();
        UUID grant = grantOf(connect(shortSession));

        String listed = webTestClient
                .get()
                .uri("/workspaces/{slug}/mcp-grants", workspace.getWorkspaceSlug())
                .header(HttpHeaders.COOKIE, cookieName + "=" + passkeyCookie(Instant.now()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        JsonNode row = null;
        for (JsonNode candidate : JSON.readTree(Objects.requireNonNull(listed))) {
            if (candidate.path("id").asString().equals(grant.toString())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(Instant.parse(
                        Objects.requireNonNull(row).path("passkeyBackedUntil").asString()))
                .isEqualTo(sessionEnd);
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
