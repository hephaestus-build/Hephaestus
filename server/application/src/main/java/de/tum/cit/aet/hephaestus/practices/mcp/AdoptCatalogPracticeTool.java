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
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code adopt_catalog_practice}: adds a catalog practice exactly as previewed, as {@code CatalogAdoptionController.adopt} does. */
@Component
@McpToolFor(controller = CatalogAdoptionController.class, method = "adopt")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class AdoptCatalogPracticeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "previewEtag"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
                            "previewEtag": {"type": "string", "description": "The previewEtag that preview_catalog_practice returned"}}}
            """;

    private final CatalogAdoptionService adoption;
    private final CatalogOriginPresenter presenter;
    private final McpSchema.Tool definition;

    AdoptCatalogPracticeTool(CatalogAdoptionService adoption, CatalogOriginPresenter presenter, JsonMapper jsonMapper) {
        this.adoption = adoption;
        this.presenter = presenter;
        this.definition = McpTools.writeTool(
                jsonMapper,
                "adopt_catalog_practice",
                "Add a catalog practice",
                "Adds a catalog practice to this workspace exactly as preview_catalog_practice showed it. If the"
                        + " catalog or the workspace changed since the preview, it changes nothing: preview again."
                        + " Undo it with remove_practice; a practice with review history is set to reviewed by"
                        + " nobody with update_practice instead.",
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
                false,
                false);
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
        var adopted = adoption.adopt(
                context, McpTools.requiredString(arguments, "slug"), McpTools.requiredString(arguments, "previewEtag"));
        return McpTools.result(McpProjections.Practice.of(presenter.present(context.id(), adopted)));
    }
}
