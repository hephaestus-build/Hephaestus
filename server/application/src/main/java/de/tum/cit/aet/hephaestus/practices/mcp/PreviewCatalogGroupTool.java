package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogAdoptionController;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogAdoptionService;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogGroupAdoptionPreviewDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code preview_catalog_group}: what adopting a catalog group would add and move, as {@code CatalogAdoptionController.previewGroup} reads it. */
@Component
@McpToolFor(controller = CatalogAdoptionController.class, method = "previewGroup")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class PreviewCatalogGroupTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "disposition", "practices", "actions", "previewEtag"],
             "additionalProperties": false,
             "properties": {
               "slug": {"type": "string"}, "disposition": {"type": "string"},
               "practices": {"type": "array", "items": {"type": "object", "required": ["slug", "kind", "availability", "groupSlug"],
                 "additionalProperties": false,
                 "properties": {"slug": {"type": "string"}, "kind": {"type": "string"}, "availability": {"type": "string"},
                                "groupSlug": {"type": ["string", "null"]}}}},
               "actions": {"type": "array", "items": {"type": "object", "required": ["slug", "action"], "additionalProperties": false,
                 "properties": {"slug": {"type": "string"}, "action": {"type": "string"}}}},
               "previewEtag": {"type": "string", "description": "Send this to adopt_catalog_group, which adopts exactly this preview"}}}
            """;

    private final CatalogAdoptionService adoption;
    private final McpSchema.Tool definition;

    PreviewCatalogGroupTool(CatalogAdoptionService adoption, JsonMapper jsonMapper) {
        this.adoption = adoption;
        this.definition = McpTools.readTool(
                jsonMapper,
                "preview_catalog_group",
                "Preview a catalog group",
                "Returns what adding a catalog group would do: each practice of the group with its availability, the"
                        + " action for each practice, and the previewEtag that adopt_catalog_group needs.",
                INPUT_SCHEMA,
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        CatalogGroupAdoptionPreviewDTO preview = adoption.previewGroup(
                        WorkspaceContextHolder.getContext(), McpTools.requiredString(arguments, "slug"))
                .preview();
        return McpTools.result(new Result(
                preview.slug(),
                preview.disposition().name(),
                preview.practices().stream()
                        .map(practice -> new Result.Practice(
                                practice.slug(),
                                practice.kind(),
                                practice.availability().name(),
                                practice.group().slug()))
                        .toList(),
                preview.actions().stream()
                        .map(action ->
                                new Result.Action(action.slug(), action.action().name()))
                        .toList(),
                preview.etag()));
    }

    record Result(String slug, String disposition, List<Practice> practices, List<Action> actions, String previewEtag) {
        record Practice(
                String slug,
                String kind,
                String availability,
                @Nullable String groupSlug) {}

        record Action(String slug, String action) {}
    }
}
