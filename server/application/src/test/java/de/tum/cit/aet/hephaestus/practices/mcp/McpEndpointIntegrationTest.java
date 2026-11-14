package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The workspace MCP endpoint over real HTTP: routing, the workspace filter, and method security on a tool. */
@TestPropertySource(properties = "hephaestus.mcp.enabled=true")
class McpEndpointIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String LIST_PRACTICES = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "list_practices", "arguments": {}}}
            """;

    @Autowired
    private WebTestClient client;

    @Autowired
    private PracticeVersionService versions;

    private WebTestClient.RequestHeadersSpec<?> call(String slug, String body) {
        return client.post()
                .uri("/workspaces/{workspace}/mcp", slug)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body);
    }

    @Test
    @WithAdminUser
    void shouldListTheWorkspacePracticesWhenAnAdminCallsTheTool() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("mcp-list", "MCP list", "mcp-list-org", AccountType.ORG, owner);
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(),
                "mcp-listed",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));

        call(workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.isError")
                .doesNotExist()
                .jsonPath("$.result.structuredContent.practices[?(@.slug == 'mcp-listed')].versionNumber")
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void shouldListTheToolWithItsOutputSchema() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("mcp-tools", "MCP tools", "mcp-tools-org", AccountType.ORG, owner);

        call(workspace.getWorkspaceSlug(), """
                        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
                        """)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.tools[?(@.name == 'list_practices')].outputSchema.required[0]")
                .isEqualTo("practices");
    }

    @Test
    @WithMentorUser
    void shouldRefuseTheToolWithItsReasonWhenAMemberIsNotAnAdmin() {
        var mentor = persistUser("mentor");
        var owner = persistUser("mcp-owner");
        var workspace = createWorkspace("mcp-member", "MCP member", "mcp-member-org", AccountType.ORG, owner);
        ensureMembership(workspace, mentor, Membership.WorkspaceRole.MEMBER);

        call(workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.isError")
                .isEqualTo(true)
                .jsonPath("$.result.content[0].text")
                .isEqualTo("Your role in this workspace does not allow this tool.");
    }

    @Test
    @WithMentorUser
    void shouldAnswerNotFoundWhenANonMemberCallsTheEndpoint() {
        persistUser("mentor");
        var owner = persistUser("mcp-stranger-owner");
        var workspace = createWorkspace("mcp-private", "MCP private", "mcp-private-org", AccountType.ORG, owner);

        call(workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldAnswerUnauthorizedWithoutABearerToken() {
        var owner = persistUser("mcp-anonymous-owner");
        var workspace = createWorkspace("mcp-anon", "MCP anon", "mcp-anon-org", AccountType.ORG, owner);

        call(workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRefuseABrowserRequestFromAnotherOrigin() {
        var owner = persistUser("admin");
        var workspace = createWorkspace("mcp-origin", "MCP origin", "mcp-origin-org", AccountType.ORG, owner);

        call(workspace.getWorkspaceSlug(), LIST_PRACTICES)
                .headers(TestAuthUtils.withCurrentUser())
                .header("Origin", "https://attacker.example")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
