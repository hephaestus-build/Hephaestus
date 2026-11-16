package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsController;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code set_feedback_delivery}: pauses or resumes feedback delivery, as the review settings update saves it. */
@Component
@McpToolFor(controller = PracticeReviewSettingsController.class, method = "updatePracticeReviewSettings")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class SetFeedbackDeliveryTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["etag", "deliveryStatus"], "additionalProperties": false,
             "properties": {
               %s,
               "deliveryStatus": {"enum": ["ACTIVE", "PAUSED"], "description": "PAUSED stops new feedback from leaving; ACTIVE resumes it"}}}
            """.formatted(ReviewSettingsChanges.ETAG_PROPERTY);

    private final ReviewSettingsChanges changes;
    private final McpSchema.Tool definition;

    SetFeedbackDeliveryTool(ReviewSettingsChanges changes, JsonMapper jsonMapper) {
        this.changes = changes;
        this.definition = McpTools.writeTool(
                jsonMapper,
                "set_feedback_delivery",
                "Pause or resume feedback",
                "Pauses the delivery of new feedback in this workspace, or resumes it. Resuming never delivers"
                        + " feedback from an older revision. PAUSED and ACTIVE undo each other.",
                INPUT_SCHEMA,
                McpProjections.REVIEW_SETTINGS_SCHEMA,
                false,
                true);
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
        request.put("deliveryStatus", arguments.get("deliveryStatus"));
        return changes.apply(arguments, request);
    }
}
