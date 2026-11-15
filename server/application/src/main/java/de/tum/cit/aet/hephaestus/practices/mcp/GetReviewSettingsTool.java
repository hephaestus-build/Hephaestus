package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsController;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsDTO;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
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

    private static final String OUTPUT_SCHEMA = """
            {"type": "object", "additionalProperties": false,
             "required": ["etag", "deliveryStatus", "defaultFeedbackMode", "deliverToMerged", "cooldownMinutes", "reviewScope", "scopeSummary"],
             "properties": {
               "etag": {"type": "string", "description": "Send this with a change to the settings"},
               "deliveryStatus": {"type": "string", "description": "ACTIVE, or PAUSED when no new feedback leaves"},
               "defaultFeedbackMode": {"type": "string"}, "deliverToMerged": {"type": "boolean"}, "cooldownMinutes": {"type": "integer"},
               "reviewScope": {"type": "object", "required": ["repositories", "people", "selectedRepositories", "accountIds"], "additionalProperties": false,
                 "properties": {"repositories": {"type": "string"}, "people": {"type": "string"},
                   "selectedRepositories": {"type": "array", "items": {"type": "object", "required": ["repositoryItemId", "baseBranches"], "additionalProperties": false,
                     "properties": {"repositoryItemId": {"type": "string"}, "baseBranches": {"type": "array", "items": {"type": "string"}}}}},
                   "accountIds": {"type": "array", "items": {"type": "string"}}}},
               "scopeSummary": {"type": "object", "required": ["selectedRepositories", "monitoredRepositories", "selectedPeople", "eligiblePeople"], "additionalProperties": false,
                 "properties": {"selectedRepositories": {"type": "integer"}, "monitoredRepositories": {"type": "integer"},
                                "selectedPeople": {"type": "integer"}, "eligiblePeople": {"type": "integer"}}}}}
            """;

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
                OUTPUT_SCHEMA);
    }

    @Override
    public McpSchema.Tool definition() {
        return definition;
    }

    @Override
    @RequireAtLeastWorkspaceAdmin
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        PracticeReviewSettingsDTO current = settings.getSettings(WorkspaceContextHolder.getContext());
        var scope = current.reviewScope();
        var summary = current.scopeSummary();
        return McpTools.result(new Result(
                current.etag(),
                current.deliveryStatus().name(),
                current.defaultFeedbackMode().name(),
                current.deliverToMerged(),
                current.cooldownMinutes(),
                new Scope(
                        scope.repositories().name(),
                        scope.people().name(),
                        scope.selectedRepositories().stream()
                                .map(repository -> new Repository(repository.repositoryItemId(), repository.baseBranches()))
                                .toList(),
                        scope.accountIds()),
                new Summary(
                        summary.selectedRepositories(),
                        summary.monitoredRepositories(),
                        summary.selectedPeople(),
                        summary.eligiblePeople())));
    }

    record Result(
            String etag,
            String deliveryStatus,
            String defaultFeedbackMode,
            boolean deliverToMerged,
            int cooldownMinutes,
            Scope reviewScope,
            Summary scopeSummary) {}

    record Scope(String repositories, String people, List<Repository> selectedRepositories, List<String> accountIds) {}

    record Repository(String repositoryItemId, List<String> baseBranches) {}

    record Summary(int selectedRepositories, int monitoredRepositories, int selectedPeople, int eligiblePeople) {}
}
