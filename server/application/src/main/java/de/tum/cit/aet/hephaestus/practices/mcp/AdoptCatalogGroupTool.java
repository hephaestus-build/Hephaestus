package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogAdoptionController;
import de.tum.cit.aet.hephaestus.practices.curated.adoption.CatalogAdoptionService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code adopt_catalog_group}: adopts a catalog group exactly as previewed, as {@code CatalogAdoptionController.adoptGroup} does. */
@Component
@McpToolFor(controller = CatalogAdoptionController.class, method = "adoptGroup")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class AdoptCatalogGroupTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "previewEtag"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
                            "previewEtag": {"type": "string", "description": "The previewEtag that preview_catalog_group returned"}}}
            """;

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "required": ["added", "moved"], "additionalProperties": false,
             "properties": {"added": {"type": "array", "items": %1$s}, "moved": {"type": "array", "items": %1$s}}}
            """.formatted(McpProjections.PRACTICE_SCHEMA);

    private final CatalogAdoptionService adoption;
    private final CatalogOriginPresenter presenter;
    private final McpSchema.Tool definition;

    AdoptCatalogGroupTool(CatalogAdoptionService adoption, CatalogOriginPresenter presenter, JsonMapper jsonMapper) {
        this.adoption = adoption;
        this.presenter = presenter;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "adopt_catalog_group",
                        "Add a catalog group",
                        "Adds every available practice of a catalog group to this workspace and moves the group's practices"
                                + " that the workspace already has into it, exactly as preview_catalog_group showed it. If the"
                                + " catalog or the workspace changed since the preview, it changes nothing: preview again."
                                + " Before you adopt, keep what list_practices returns. Undo it with remove_practice for each"
                                + " added practice and with move_practice_to_group for each moved practice and its earlier"
                                + " group. A group that the adoption created stays, without practices."),
                INPUT_SCHEMA,
                OUTPUT_SCHEMA,
                McpTools.Effect.ADDS);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        var result = adoption.adoptGroup(
                context, McpTools.requiredString(arguments, "slug"), McpTools.requiredString(arguments, "previewEtag"));
        return McpTools.result(new Result(
                result.added().stream()
                        .map(practice -> McpProjections.Practice.of(presenter.present(context.id(), practice)))
                        .toList(),
                result.moved().stream()
                        .map(practice -> McpProjections.Practice.of(presenter.present(context.id(), practice)))
                        .toList()));
    }

    record Result(List<McpProjections.Practice> added, List<McpProjections.Practice> moved) {}
}
