package de.tum.cit.aet.hephaestus.practices.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpAuthorizationFlow;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The practice tools of the workspace MCP endpoint, called with a real MCP client token. */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class McpPracticeToolsIntegrationTest extends RealAuthIntegrationTest {

    private static final String LIST_PRACTICES = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "list_practices", "arguments": {}}}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private PracticeVersionService versions;

    @Autowired
    private McpStatelessSyncServer server;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private McpAuthorizationFlow flow;
    private Workspace workspace;
    private long accountId;
    private String accessToken;

    @BeforeEach
    void connectAnAdminsClient() {
        flow = new McpAuthorizationFlow(webTestClient, cookieName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cookie = flow.signIn("mcp-tools-" + suffix);
        accountId = McpAuthorizationFlow.accountId(cookie);
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mcp-tools-" + suffix);
        workspace.setDisplayName("MCP tools");
        workspace.setAccountLogin("mcp-tools-org-" + suffix);
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace = workspaceRepository.save(workspace);
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountId));
        membership.setRole(Membership.WorkspaceRole.ADMIN);
        membershipRepository.save(membership);
        accessToken = flow.connect(cookie, workspace.getWorkspaceSlug()).accessToken();
    }

    @Test
    void shouldListTheWorkspacePracticesWithTheirVersions() {
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "mcp-listed", new ItemKind("scm.pull_request"), DefinitionTestFixtures.definition(), null, 0, null));

        flow.callTool(accessToken, workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.isError")
                .isEqualTo(false)
                .jsonPath("$.result.structuredContent.practices[?(@.slug == 'mcp-listed')].versionNumber")
                .isEqualTo(1);
    }

    @Test
    void shouldListTheToolWithItsOutputSchema() {
        flow.callTool(accessToken, workspace.getWorkspaceSlug(), """
                        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
                        """)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.tools[?(@.name == 'list_practices')].outputSchema.required[0]")
                .isEqualTo("practices");
    }

    /** Roles are checked on every call: a demotion after consent takes effect on the next call. */
    @Test
    void shouldRefuseTheToolWithItsReasonAfterTheAdminIsDemoted() {
        Membership membership = membershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId)
                .orElseThrow();
        membership.setRole(Membership.WorkspaceRole.MEMBER);
        membershipRepository.save(membership);

        flow.callTool(accessToken, workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.isError")
                .isEqualTo(true)
                .jsonPath("$.result.content[0].text")
                .isEqualTo("Your role in this workspace does not allow this tool.");
    }

    /** Every object in a tool's output schema closes its properties and requires them all, so the SDK's
     * validation of each result fails on a field that the contract does not name or a field that is missing. */
    @Test
    void shouldDeclareClosedOutputSchemas() {
        assertThat(server.listTools()).isNotEmpty();
        for (McpSchema.Tool tool : server.listTools()) {
            assertThat(tool.outputSchema()).as(tool.name()).isNotNull();
            assertClosed(tool.name(), tool.outputSchema());
        }
    }

    private static void assertClosed(String path, Object schema) {
        if (!(schema instanceof Map<?, ?> node)) {
            return;
        }
        if (node.get("properties") instanceof Map<?, ?> properties) {
            assertThat(node.get("additionalProperties")).as(path + " closes its properties").isEqualTo(false);
            Set<?> required = node.get("required") instanceof List<?> names ? new HashSet<>(names) : Set.of();
            assertThat(required).as(path + " requires every property").isEqualTo(new HashSet<>(properties.keySet()));
            properties.forEach((name, child) -> assertClosed(path + "." + name, child));
        }
        if (node.get("items") != null) {
            assertClosed(path + "[]", node.get("items"));
        }
    }

    @Test
    void shouldRefuseABrowserRequestFromAnotherOrigin() {
        webTestClient.post()
                .uri("/workspaces/{slug}/mcp", workspace.getWorkspaceSlug())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(LIST_PRACTICES)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
