package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogController;
import de.tum.cit.aet.hephaestus.practices.PracticeReleaseService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code reset_practice}: returns a catalog practice to its catalog version, as {@code PracticeCatalogController.reset} does. */
@Component
@McpToolFor(controller = PracticeCatalogController.class, method = "reset")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ResetPracticeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "resetEtag"], "additionalProperties": false,
             "properties": {"slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
                            "resetEtag": {"type": "string", "description": "The resetEtag that preview_practice_reset returned"}}}
            """;

    private final PracticeReleaseService releases;
    private final CatalogOriginPresenter presenter;
    private final McpSchema.Tool definition;

    ResetPracticeTool(PracticeReleaseService releases, CatalogOriginPresenter presenter, JsonMapper jsonMapper) {
        this.releases = releases;
        this.presenter = presenter;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "reset_practice",
                        "Reset a practice to the catalog",
                        "Returns a practice that came from the catalog to the catalog version that preview_practice_reset"
                                + " showed, and drops the workspace's changes to it. If the practice or the catalog changed"
                                + " since the preview, it changes nothing. Before you reset, keep the definition that"
                                + " get_practice returns: undo the reset with update_practice and that definition."),
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
                McpTools.Effect.REPLACES);
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
        var reset = releases.reset(
                context,
                McpTools.requiredString(arguments, "slug"),
                EntityTagPrecondition.parse(McpTools.requiredString(arguments, "resetEtag")));
        return McpTools.result(McpProjections.Practice.of(presenter.present(context.id(), reset)));
    }
}
