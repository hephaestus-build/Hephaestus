package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsController;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewField;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code set_default_feedback_mode}: the workspace's default feedback mode, as the review settings update saves it. */
@Component
@McpToolFor(controller = PracticeReviewSettingsController.class, method = "updatePracticeReviewSettings")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class SetDefaultFeedbackModeTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["etag", "defaultFeedbackMode"], "additionalProperties": false,
             "properties": {
               %s,
               "defaultFeedbackMode": {"enum": ["OFF", "APPROVE_FIRST", "AUTOMATIC", null],
                 "description": "The mode of every practice and group that sets none; null returns to the instance's default"}}}
            """.formatted(ReviewSettingsChanges.ETAG_PROPERTY);

    private final ReviewSettingsChanges changes;
    private final McpSchema.Tool definition;

    SetDefaultFeedbackModeTool(ReviewSettingsChanges changes, JsonMapper jsonMapper) {
        this.changes = changes;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "set_default_feedback_mode",
                        "Set the default feedback mode",
                        "Sets the feedback mode of every practice and group in this workspace that sets none, or null to"
                                + " return to the instance's default. Undo it with this tool and the earlier"
                                + " defaultFeedbackModeOverride from get_review_settings, null included."),
                INPUT_SCHEMA,
                McpProjections.REVIEW_SETTINGS_SCHEMA,
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
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_REVIEW_SETTINGS")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        Map<String, Object> request = new HashMap<>();
        Object mode = arguments.get("defaultFeedbackMode");
        if (mode == null) {
            request.put("reset", List.of(PracticeReviewField.DEFAULT_FEEDBACK_MODE.name()));
        } else {
            request.put("defaultFeedbackMode", mode);
        }
        return changes.apply(arguments, request);
    }
}
