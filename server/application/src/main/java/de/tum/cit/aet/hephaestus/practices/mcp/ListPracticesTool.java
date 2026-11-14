package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code list_practices}: the workspace's practices, as {@code PracticeCatalogController.list} reads them. */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ListPracticesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "properties": {}, "additionalProperties": false}
            """;

    private static final String OUTPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["practices"],
              "additionalProperties": false,
              "properties": {
                "practices": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["slug", "name", "kind", "groupSlug", "position", "versionNumber", "versionId", "reviewedBy", "feedbackMode"],
                    "additionalProperties": false,
                    "properties": {
                      "slug": {"type": "string"},
                      "name": {"type": ["string", "null"]},
                      "kind": {"type": "string"},
                      "groupSlug": {"type": ["string", "null"]},
                      "position": {"type": "integer"},
                      "versionNumber": {"type": "integer"},
                      "versionId": {"type": "string"},
                      "reviewedBy": {"type": ["string", "null"], "enum": ["HEPHAESTUS", "PEOPLE", "NOBODY", null]},
                      "feedbackMode": {
                        "type": "object",
                        "required": ["override", "effective", "source"],
                        "additionalProperties": false,
                        "properties": {
                          "override": {"type": ["string", "null"]},
                          "effective": {"type": "string"},
                          "source": {"type": "string"}
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private final PracticeService practices;
    private final CatalogOriginPresenter presenter;
    private final McpSchema.Tool definition;

    ListPracticesTool(PracticeService practices, CatalogOriginPresenter presenter, JsonMapper jsonMapper) {
        this.practices = practices;
        this.presenter = presenter;
        var mapper = new JacksonMcpJsonMapper(jsonMapper);
        this.definition = McpSchema.Tool.builder()
                .name("list_practices")
                .title("List workspace practices")
                .description("Lists the practices of this workspace with their version, group, reviewer and feedback mode.")
                .inputSchema(mapper, INPUT_SCHEMA)
                .outputSchema(mapper, OUTPUT_SCHEMA)
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                .build();
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        List<Practice> rows = presenter.presentPractices(context.id(), practices.listPractices(context, null)).stream()
                .map(ListPracticesTool::row)
                .toList();
        return McpSchema.CallToolResult.builder()
                .structuredContent(new Result(rows))
                .build();
    }

    private static Practice row(PracticeDTO practice) {
        var version = practice.currentVersion();
        var definition = version.definition();
        var mode = practice.feedbackMode();
        return new Practice(
                practice.slug(),
                version.name(),
                practice.kind(),
                practice.groupSlug(),
                practice.position(),
                version.number(),
                version.id(),
                definition == null ? null : definition.reviewedBy().by().name(),
                new FeedbackMode(
                        mode.override() == null ? null : mode.override().name(),
                        mode.effective().name(),
                        mode.source().name()));
    }

    record Result(List<Practice> practices) {}

    record Practice(
            String slug,
            @Nullable String name,
            String kind,
            @Nullable String groupSlug,
            int position,
            int versionNumber,
            String versionId,
            @Nullable String reviewedBy,
            FeedbackMode feedbackMode) {}

    record FeedbackMode(@Nullable String override, String effective, String source) {}
}
