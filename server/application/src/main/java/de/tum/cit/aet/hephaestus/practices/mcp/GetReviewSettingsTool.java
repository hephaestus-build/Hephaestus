package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsController;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code get_review_settings}: who and what Hephaestus reviews, as {@code PracticeReviewSettingsController} reads it. */
@Component
@McpToolFor(controller = PracticeReviewSettingsController.class, method = "getPracticeReviewSettings")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class GetReviewSettingsTool implements McpTool {

    private final PracticeReviewSettingsService settings;
    private final McpSchema.Tool definition;

    GetReviewSettingsTool(PracticeReviewSettingsService settings, JsonMapper jsonMapper) {
        this.settings = settings;
        this.definition = McpTools.readTool(
                jsonMapper,
                "get_review_settings",
                "Get review settings",
                "Returns the repositories and people that Hephaestus reviews in this workspace, whether feedback"
                        + " delivery is paused, and the default feedback mode. People are named by account id; list_members"
                        + " maps them to provider logins.",
                McpTools.NO_INPUT,
                McpProjections.REVIEW_SETTINGS_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return McpTools.result(
                McpProjections.ReviewSettings.of(settings.getSettings(WorkspaceContextHolder.getContext())));
    }
}
