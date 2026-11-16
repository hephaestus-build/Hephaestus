package de.tum.cit.aet.hephaestus.practices.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpAuthorizationFlow;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.GroupAttributes;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write tools of the workspace MCP endpoint (ADR 0110 § Tools): each changes the workspace through the service of
 * the HTTP handler it stands for, refuses what that handler refuses with the same reason, records the client on the
 * config audit row, and has a reverse. A grant without {@code practices:write} cannot call them.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class McpWriteToolsIntegrationTest extends RealAuthIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private PracticeGroupService groups;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private McpAuthorizationFlow flow;
    private Workspace workspace;
    private long accountId;
    private String cookie;
    private String writeToken;

    @BeforeEach
    void connectAnAdminsClientThatMayWrite() {
        flow = new McpAuthorizationFlow(webTestClient, cookieName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cookie = flow.signIn("mcp-write-" + suffix);
        accountId = McpAuthorizationFlow.accountId(cookie);
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mcp-write-" + suffix);
        workspace.setDisplayName("MCP writes");
        workspace.setAccountLogin("mcp-write-org-" + suffix);
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace = workspaceRepository.save(workspace);
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), accountId));
        membership.setRole(Membership.WorkspaceRole.ADMIN);
        membershipRepository.save(membership);
        writeToken = flow.connect(cookie, workspace.getWorkspaceSlug(), "practices:read practices:write")
                .accessToken();
    }

    @Test
    void shouldAskForTheWriteScopeBeforeAWriteToolRunsWhenTheGrantOnlyReads() {
        String readToken = flow.connect(cookie, workspace.getWorkspaceSlug()).accessToken();
        double refusedBefore = counter("mcp.auth.refusals", "reason", "insufficient_scope");
        double readsBefore = counter("mcp.tool.calls", "tool", "get_review_settings");

        String challenge = flow.callTool(
                        readToken,
                        workspace.getWorkspaceSlug(),
                        request("set_feedback_delivery", Map.of("etag", "\"any\"", "deliveryStatus", "PAUSED")))
                .expectStatus()
                .isForbidden()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.WWW_AUTHENTICATE);

        assertThat(challenge)
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"practices:write\"")
                .contains("resource_metadata=");
        assertThat(setting("practice_delivery_status")).isNotEqualTo("PAUSED");
        assertThat(call(readToken, "get_review_settings", Map.of())
                        .path("isError")
                        .asBoolean())
                .isFalse();
        assertThat(counter("mcp.auth.refusals", "reason", "insufficient_scope")).isEqualTo(refusedBefore + 1);
        assertThat(counter("mcp.tool.calls", "tool", "get_review_settings")).isEqualTo(readsBefore + 1);
    }

    /** The SDK reads a malformed byte as a replacement character, so the scope check must read the body the same way. */
    @Test
    void shouldAskForTheWriteScopeWhenTheBodyIsNotValidUtf8() {
        String readToken = flow.connect(cookie, workspace.getWorkspaceSlug()).accessToken();
        byte[] text = request("set_feedback_delivery", Map.of("etag", "\"MALFORMED\"", "deliveryStatus", "PAUSED"))
                .getBytes(StandardCharsets.UTF_8);
        String marker = "MALFORMED";
        int at = new String(text, StandardCharsets.UTF_8).indexOf(marker);
        byte[] body = new byte[text.length - marker.length() + 1];
        System.arraycopy(text, 0, body, 0, at);
        body[at] = (byte) 0xFF;
        System.arraycopy(text, at + marker.length(), body, at + 1, text.length - at - marker.length());

        flow.callTool(readToken, workspace.getWorkspaceSlug(), body)
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(setting("practice_delivery_status")).isNotEqualTo("PAUSED");
    }

    @Test
    void shouldCreateChangeAndRemoveAPracticeAndRecordTheClientOnEachAuditRow() {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("slug", "mcp-written");
        created.put("kind", "scm.pull_request");
        created.put("definition", definition());
        JsonNode practice = structured(call(writeToken, "create_practice", created));
        assertThat(practice.path("versionNumber").asInt()).isEqualTo(1);

        JsonNode changed = structured(call(
                writeToken,
                "update_practice",
                Map.of(
                        "slug", "mcp-written",
                        "expectedVersionId", practice.path("expectedVersionId").asString(),
                        "definition", Map.of("criteria", "Each pull request names the issue it closes."))));
        assertThat(changed.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(changed.path("definition").path("criteria").asString())
                .isEqualTo("Each pull request names the issue it closes.");
        assertThat(changed.path("definition").path("name").asString())
                .isEqualTo(practice.path("definition").path("name").asString());

        JsonNode removed = structured(call(writeToken, "remove_practice", Map.of("slug", "mcp-written")));
        assertThat(removed.path("removed").asString()).isEqualTo("mcp-written");

        UUID grant = grantOf(writeToken);
        assertThat(jdbc.queryForList(
                        "SELECT action FROM config_audit_event WHERE workspace_id = ? AND entity_type = 'PRACTICE_DEFINITION'"
                                + " AND mcp_client_grant_id = ? ORDER BY id",
                        String.class,
                        workspace.getId(),
                        grant))
                .containsExactly("CREATED", "UPDATED", "DELETED");
    }

    @Test
    void shouldRefuseWhatThePracticeEditorRefusesWithItsReason() {
        Map<String, Object> definition = new HashMap<>(definition());
        definition.remove("criteria");
        JsonNode refused = call(
                writeToken,
                "create_practice",
                Map.of("slug", "mcp-invalid", "kind", "scm.pull_request", "definition", definition));
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).doesNotContain("The tool failed");

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("slug", "mcp-stale");
        created.put("kind", "scm.pull_request");
        created.put("definition", definition());
        JsonNode practice = structured(call(writeToken, "create_practice", created));
        structured(call(
                writeToken,
                "update_practice",
                Map.of(
                        "slug", "mcp-stale",
                        "expectedVersionId", practice.path("expectedVersionId").asString(),
                        "definition", Map.of("criteria", "First change."))));
        JsonNode stale = call(
                writeToken,
                "update_practice",
                Map.of(
                        "slug", "mcp-stale",
                        "expectedVersionId", practice.path("expectedVersionId").asString(),
                        "definition", Map.of("criteria", "Second change from an old read.")));
        assertThat(stale.path("isError").asBoolean()).isTrue();
        assertThat(text(stale)).contains("The practice version changed");

        JsonNode unknown = call(writeToken, "get_practice", Map.of("slug", "mcp-no-such-practice"));
        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(text(unknown)).doesNotContain("The tool failed");
    }

    @Test
    void shouldSetAndInheritAPracticesFeedbackModeAndMoveItBetweenGroups() {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("slug", "mcp-moded");
        created.put("kind", "scm.pull_request");
        created.put("definition", definition());
        structured(call(writeToken, "create_practice", created));
        groups.createGroup(context(), "mcp-group", new GroupAttributes("MCP group", null, null, null, null), true);

        JsonNode off = structured(
                call(writeToken, "set_practice_feedback_mode", Map.of("slug", "mcp-moded", "feedbackMode", "OFF")));
        assertThat(off.path("feedbackMode").path("override").asString()).isEqualTo("OFF");
        Map<String, Object> inherit = new HashMap<>();
        inherit.put("slug", "mcp-moded");
        inherit.put("feedbackMode", null);
        JsonNode inherited = structured(call(writeToken, "set_practice_feedback_mode", inherit));
        assertThat(inherited.path("feedbackMode").path("override").isNull()).isTrue();

        JsonNode grouped = structured(
                call(writeToken, "move_practice_to_group", Map.of("slug", "mcp-moded", "groupSlug", "mcp-group")));
        assertThat(grouped.path("groupSlug").asString()).isEqualTo("mcp-group");
        Map<String, Object> ungroup = new HashMap<>();
        ungroup.put("slug", "mcp-moded");
        ungroup.put("groupSlug", null);
        JsonNode ungrouped = structured(call(writeToken, "move_practice_to_group", ungroup));
        assertThat(ungrouped.path("groupSlug").isNull()).isTrue();
    }

    @Test
    void shouldAdoptACatalogPracticeAsPreviewedAndResetItAfterAChange() {
        String slug =
                catalog.effectiveCatalog().installablePractices().getFirst().slug();
        JsonNode preview = structured(call(writeToken, "preview_catalog_practice", Map.of("slug", slug)));

        JsonNode adopted = structured(call(
                writeToken,
                "adopt_catalog_practice",
                Map.of("slug", slug, "previewEtag", preview.path("previewEtag").asString())));
        assertThat(adopted.path("slug").asString()).isEqualTo(slug);
        JsonNode again = call(
                writeToken,
                "adopt_catalog_practice",
                Map.of("slug", slug, "previewEtag", preview.path("previewEtag").asString()));
        assertThat(again.path("isError").asBoolean()).isTrue();

        JsonNode customized = structured(call(
                writeToken,
                "update_practice",
                Map.of(
                        "slug", slug,
                        "expectedVersionId", adopted.path("expectedVersionId").asString(),
                        "definition", Map.of("criteria", "The workspace's own criteria."))));
        assertThat(customized.path("localEdits").size()).isPositive();
        JsonNode resetPreview = structured(call(writeToken, "preview_practice_reset", Map.of("slug", slug)));
        JsonNode reset = structured(call(
                writeToken,
                "reset_practice",
                Map.of("slug", slug, "resetEtag", resetPreview.path("resetEtag").asString())));
        assertThat(reset.path("localEdits").size()).isZero();
        assertThat(reset.path("definition").path("criteria").asString())
                .isEqualTo(adopted.path("definition").path("criteria").asString());
    }

    @Test
    void shouldPauseAndResumeFeedbackAndChangeTheScopeAndDefaultModeWithTheCurrentEtag() {
        JsonNode settings = structured(call(writeToken, "get_review_settings", Map.of()));

        JsonNode paused = structured(call(
                writeToken,
                "set_feedback_delivery",
                Map.of("etag", settings.path("etag").asString(), "deliveryStatus", "PAUSED")));
        assertThat(paused.path("deliveryStatus").asString()).isEqualTo("PAUSED");
        JsonNode stale = call(
                writeToken,
                "set_feedback_delivery",
                Map.of("etag", settings.path("etag").asString(), "deliveryStatus", "ACTIVE"));
        assertThat(stale.path("isError").asBoolean()).isTrue();
        assertThat(setting("practice_delivery_status")).isEqualTo("PAUSED");
        JsonNode resumed = structured(call(
                writeToken,
                "set_feedback_delivery",
                Map.of("etag", paused.path("etag").asString(), "deliveryStatus", "ACTIVE")));
        assertThat(resumed.path("deliveryStatus").asString()).isEqualTo("ACTIVE");

        JsonNode scoped = structured(call(
                writeToken,
                "set_review_scope",
                Map.of(
                        "etag", resumed.path("etag").asString(),
                        "reviewScope",
                                Map.of(
                                        "repositories",
                                        "ALL",
                                        "people",
                                        "SELECTED",
                                        "selectedRepositories",
                                        List.of(),
                                        "accountIds",
                                        List.of(String.valueOf(accountId))))));
        assertThat(scoped.path("reviewScope").path("people").asString()).isEqualTo("SELECTED");
        assertThat(scoped.path("reviewScope").path("accountIds").get(0).asString())
                .isEqualTo(String.valueOf(accountId));

        JsonNode approveFirst = structured(call(
                writeToken,
                "set_default_feedback_mode",
                Map.of("etag", scoped.path("etag").asString(), "defaultFeedbackMode", "APPROVE_FIRST")));
        assertThat(approveFirst.path("defaultFeedbackMode").asString()).isEqualTo("APPROVE_FIRST");
        Map<String, Object> inherit = new HashMap<>();
        inherit.put("etag", approveFirst.path("etag").asString());
        inherit.put("defaultFeedbackMode", null);
        structured(call(writeToken, "set_default_feedback_mode", inherit));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE workspace_id = ?"
                                + " AND entity_type = 'PRACTICE_REVIEW_SETTINGS' AND mcp_client_grant_id = ?",
                        Long.class,
                        workspace.getId(),
                        grantOf(writeToken)))
                .isEqualTo(5L);
    }

    private JsonNode call(String token, String tool, Map<String, Object> arguments) {
        String body = flow.callTool(token, workspace.getWorkspaceSlug(), request(tool, arguments))
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return JSON.readTree(Objects.requireNonNull(body)).path("result");
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

    private static String request(String tool, Map<String, Object> arguments) {
        return JSON.writeValueAsString(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "tools/call",
                "params",
                Map.of("name", tool, "arguments", arguments)));
    }

    private UUID grantOf(String accessToken) {
        String session = McpAuthorizationFlow.payload(accessToken).path("sid").asString();
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM mcp_client_grant WHERE client_session_id = ?", UUID.class, UUID.fromString(session)));
    }

    private String setting(String column) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT " + column + " FROM workspace WHERE id = ?", String.class, workspace.getId()));
    }

    /** The count of one tag value of a meter; for tool calls, only the calls that succeeded. */
    private double counter(String name, String tag, String value) {
        return meterRegistry.find(name).tag(tag, value).counters().stream()
                .filter(counter -> !"mcp.tool.calls".equals(name)
                        || "ok".equals(counter.getId().getTag("outcome")))
                .mapToDouble(Counter::count)
                .sum();
    }

    /** The fixture definition in the shape that a client sends. */
    private static Map<String, Object> definition() {
        return JSON.convertValue(DefinitionTestFixtures.definition(), new TypeReference<Map<String, Object>>() {});
    }

    private WorkspaceContext context() {
        return WorkspaceContext.fromWorkspace(workspace, Set.of(), null);
    }
}
