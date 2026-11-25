package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST API refuses an MCP client token in every configuration (ADR 0110 update of 2026-11-25). Here the web
 * app's audience is the MCP resource of one workspace, so the audience check alone would let that workspace's MCP
 * token through.
 */
@TestPropertySource(
        properties = {
            "hephaestus.auth.dev-login-enabled=true",
            "hephaestus.auth.audience=https://hephaestus.build/api/workspaces/mcp-audience-overlap/mcp"
        })
class McpTokenRestRefusalIntegrationTest extends RealAuthIntegrationTest {

    private static final String SLUG = "mcp-audience-overlap";
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
    private AuthProperties authProperties;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void shouldRefuseAnMcpClientTokenAtTheRestApiWhenTheAudiencesOverlap() {
        McpAuthorizationFlow flow = new McpAuthorizationFlow(webTestClient, cookieName);
        long accountId = McpAuthorizationFlow.accountId(flow.signIn("mcp-audience-overlap"));
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(SLUG);
        workspace.setDisplayName("MCP audience overlap");
        workspace.setAccountLogin("mcp-audience-overlap-org");
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace = workspaceRepository.save(workspace);
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountId));
        membership.setRole(Membership.WorkspaceRole.ADMIN);
        membershipRepository.save(membership);
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.setAppRole(Account.AppRole.APP_ADMIN);
        accountRepository.save(account);
        Instant now = Instant.now();
        String browser = jwtIssuer
                .issue(accountId, TokenConstraints.browserSession(now.plus(Duration.ofHours(8)), now, null, null), null)
                .value();

        McpAuthorizationFlow.Tokens tokens = flow.connect(browser, SLUG);
        assertThat(authProperties.audience()).isEqualTo(McpAuthorizationFlow.resource(SLUG));
        assertThat(McpAuthorizationFlow.payload(tokens.accessToken())
                        .path("aud")
                        .toString())
                .contains(authProperties.audience());

        // The browser session reaches both endpoints, so each refusal below is about the token.
        get("/admin/settings", HttpHeaders.COOKIE, cookieName + "=" + browser)
                .isOk()
                .expectBody(Void.class);
        get("/workspaces/" + SLUG + "/mcp-grants", HttpHeaders.COOKIE, cookieName + "=" + browser)
                .isOk()
                .expectBody(Void.class);
        for (String path : new String[] {"/admin/settings", "/workspaces/" + SLUG + "/mcp-grants"}) {
            get(path, HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .isUnauthorized()
                    .expectBody(Void.class);
            get(path, HttpHeaders.COOKIE, cookieName + "=" + tokens.accessToken())
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        // The MCP endpoint still serves the client, and still refuses the browser's token.
        String answer = flow.callTool(tokens.accessToken(), SLUG, GET_REVIEW_SETTINGS)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(JsonMapper.builder()
                        .build()
                        .readTree(Objects.requireNonNull(answer))
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isFalse();
        flow.callTool(browser, SLUG, GET_REVIEW_SETTINGS)
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    private StatusAssertions get(String path, String header, String value) {
        return webTestClient.get().uri(path).header(header, value).exchange().expectStatus();
    }
}
