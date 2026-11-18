package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.mcp.McpTool;
import de.tum.cit.aet.hephaestus.core.mcp.McpToolFor;
import de.tum.cit.aet.hephaestus.core.mcp.McpTools;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.review.PracticeTrialController;
import de.tum.cit.aet.hephaestus.practices.review.PracticeTrialService;
import de.tum.cit.aet.hephaestus.practices.review.dto.StartTrialRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.TrialDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code try_practice}: starts a trial as {@code PracticeTrialController.start} does, then waits up to
 * {@code waitSeconds} for it to end. MCP stays stateless (ADR 0110 decision 1): a trial still running is returned with
 * its id, and {@code get_practice_trial} reads it later.
 */
@Component
@McpToolFor(controller = PracticeTrialController.class, method = "start")
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class TryPracticeTool implements McpTool {

    static final int MAX_WAIT_SECONDS = 50;
    private static final Duration POLL = Duration.ofSeconds(1);

    private static final String INPUT_SCHEMA = """
            {"type": "object", "required": ["practiceSlug"], "additionalProperties": false,
             "properties": {
               "practiceSlug": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,63}$"},
               "practiceVersionId": {"type": "string", "pattern": "^[1-9][0-9]*$",
                                     "description": "The version to try, as save_practice_draft returned it; without it the current version"},
               "itemUrl": {"type": "string", "maxLength": 2048,
                           "description": "The address of a GitHub pull request or issue, or a GitLab merge request or issue. Give it or itemId"},
               "itemId": {"type": "string", "pattern": "^[1-9][0-9]*$"},
               "waitSeconds": {"type": "integer", "minimum": 0, "maximum": %d,
                               "description": "How long to wait for the trial to end before the tool returns it still running. Default 30"}}}
            """.formatted(MAX_WAIT_SECONDS);

    private final PracticeTrialService trials;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final McpSchema.Tool definition;

    TryPracticeTool(PracticeTrialService trials, JsonMapper jsonMapper, Validator validator) {
        this.trials = trials;
        this.jsonMapper = McpTools.withNulls(jsonMapper);
        this.validator = validator;
        this.definition = McpTools.writeTool(
                jsonMapper,
                new McpTools.Text(
                        "try_practice",
                        "Try a practice on one item",
                        "Runs one version of a practice on one pull request, merge request or issue, as a review"
                                + " does, and returns the trial. Save the version first with save_practice_draft."
                                + " The trial counts against the workspace's AI budget, and a workspace runs only a"
                                + " few trials at once. It needs no undo: it changes no configuration, and its findings"
                                + " expire. If the trial is still running after waitSeconds, read it later with"
                                + " get_practice_trial." + McpTrial.DESCRIPTION),
                INPUT_SCHEMA,
                McpTrial.SCHEMA,
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
    @AuditExempt(reason = "A trial records its requester on its review row and changes no configuration")
    public McpSchema.CallToolResult call(Map<String, Object> arguments) {
        int waitSeconds = Math.clamp(McpTools.optionalInt(arguments, "waitSeconds", 30), 0, MAX_WAIT_SECONDS);
        var request = new HashMap<>(arguments);
        request.remove("waitSeconds");
        var start = McpTools.input(jsonMapper, validator, request, StartTrialRequestDTO.class);
        long workspaceId = WorkspaceContextHolder.getContext().id();
        long account = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UUID id = trials.start(workspaceId, start, account);
        TrialDTO trial = awaitEnd(workspaceId, id, Instant.now().plusSeconds(waitSeconds));
        return McpTools.result(jsonMapper.convertValue(
                McpTrial.of(trial, trials.mayReadFindings(workspaceId, id, account)), Map.class));
    }

    private TrialDTO awaitEnd(long workspaceId, UUID id, Instant deadline) {
        var trial = trials.get(workspaceId, id);
        while (!trial.status().isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            trial = trials.get(workspaceId, id);
        }
        return trial;
    }
}
