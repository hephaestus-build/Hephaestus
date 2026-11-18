package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.review.PracticeTrialController;
import de.tum.cit.aet.hephaestus.practices.review.PracticeTrialService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code get_practice_trial}: reads a trial as {@code PracticeTrialController.get} does, as trial output. It reads, but
 * trial output needs {@code practices:write} (ADR 0110, update of 2026-11-16).
 */
@Component
@McpToolFor(controller = PracticeTrialController.class, method = "get", needsWriteScope = true)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class GetPracticeTrialTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["trialId"], "additionalProperties": false,
             "properties": {"trialId": {"type": "string", "format": "uuid", "description": "The id that try_practice returned"}}}
            """;

    private final PracticeTrialService trials;
    private final JsonMapper jsonMapper;
    private final McpSchema.Tool definition;

    GetPracticeTrialTool(PracticeTrialService trials, JsonMapper jsonMapper) {
        this.trials = trials;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.definition = McpTools.readTool(
                jsonMapper,
                "get_practice_trial",
                "Read a trial",
                "Reads a trial that try_practice started: its status and, once it ended, what the practice found."
                        + " Call it again while the status is not terminal." + McpTrial.DESCRIPTION,
                INPUT_SCHEMA,
                McpTrial.SCHEMA);
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
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        UUID id;
        try {
            id = UUID.fromString(McpTools.requiredString(arguments, "trialId"));
        } catch (IllegalArgumentException unreadable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The argument trialId is not a trial id.");
        }
        long workspaceId = WorkspaceContextHolder.getContext().id();
        long account = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var trial = trials.get(workspaceId, id);
        return McpTools.result(jsonMapper.convertValue(
                McpTrial.of(trial, trials.mayReadDetail(workspaceId, id, account)), Map.class));
    }
}
