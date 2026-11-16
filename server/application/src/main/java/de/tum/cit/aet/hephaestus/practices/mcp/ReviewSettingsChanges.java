package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewSettingsService;
import de.tum.cit.aet.hephaestus.practices.review.UpdatePracticeReviewSettingsRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * One change to the workspace's review settings, as {@code PracticeReviewSettingsController.updatePracticeReviewSettings}
 * saves it: the request body that the web app would send, and the {@code etag} that {@code get_review_settings} returned.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class ReviewSettingsChanges {

    /** The input fragment for the settings' {@code etag}, which every settings change names. */
    static final String ETAG_PROPERTY = """
            "etag": {"type": "string", "description": "The etag that get_review_settings returned; nothing is saved if the settings changed since"}""";

    private final PracticeReviewSettingsService settings;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    ReviewSettingsChanges(PracticeReviewSettingsService settings, JsonMapper jsonMapper, Validator validator) {
        this.settings = settings;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
    }

    McpSchema.CallToolResult apply(Map<String, Object> arguments, Map<String, Object> request) {
        var input = McpTools.input(jsonMapper, validator, request, UpdatePracticeReviewSettingsRequestDTO.class);
        String etag = McpTools.requiredString(arguments, "etag");
        // get_review_settings returns the bare validator; the If-Match header carries it quoted.
        String ifMatch = etag.startsWith("\"") || etag.startsWith("W/") ? etag : EntityTagPrecondition.format(etag);
        return McpTools.result(McpProjections.ReviewSettings.of(settings.updatePracticeReview(
                WorkspaceContextHolder.getContext(), input, EntityTagPrecondition.parse(ifMatch))));
    }
}
