package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.PracticeCatalogController;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.dto.SetFeedbackModeRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code set_practice_feedback_mode}: a practice's own feedback mode, as {@code PracticeCatalogController.mode} sets it. */
@Component
@McpToolFor(controller = PracticeCatalogController.class, method = "mode")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class SetPracticeFeedbackModeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["slug", "feedbackMode"], "additionalProperties": false,
             "properties": {
               "slug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
               "feedbackMode": {"enum": ["OFF", "APPROVE_FIRST", "AUTOMATIC", null],
                 "description": "null makes the practice inherit its group's or the workspace's mode"}}}
            """;

    private final PracticeService practices;
    private final CatalogOriginPresenter presenter;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final McpSchema.Tool definition;

    SetPracticeFeedbackModeTool(
            PracticeService practices, CatalogOriginPresenter presenter, JsonMapper jsonMapper, Validator validator) {
        this.practices = practices;
        this.presenter = presenter;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "set_practice_feedback_mode",
                        "Set a practice's feedback mode",
                        "Sets how a practice's feedback reaches people: OFF, APPROVE_FIRST (an admin approves each one) or"
                                + " AUTOMATIC, or null to inherit. Undo it with this tool and the earlier feedbackMode.override"
                                + " from get_practice."),
                INPUT_SCHEMA,
                McpProjections.PRACTICE_SCHEMA,
                McpTools.Effect.CHANGES);
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
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_USAGE")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        Map<String, Object> request = new HashMap<>();
        request.put("feedbackMode", arguments.get("feedbackMode"));
        var input = McpTools.input(jsonMapper, validator, request, SetFeedbackModeRequestDTO.class);
        var changed =
                practices.setFeedbackMode(context, McpTools.requiredString(arguments, "slug"), input.feedbackMode());
        return McpTools.result(McpProjections.Practice.of(presenter.present(context.id(), changed)));
    }
}
