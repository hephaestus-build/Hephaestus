package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Workspace-admin LLM spend rollup: what this workspace spent this month (or a past month),
 * by job type and day, plus the status of both purses.
 *
 * <p>Read-only; the workspace's own cap is written through {@link WorkspaceLlmBudgetController}.
 */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/llm/usage")
@Tag(name = "LLM Usage", description = "Per-workspace LLM spend rollup and budget status")
@Validated
public class LlmUsageController {

    private final LlmUsageService llmUsageService;

    public LlmUsageController(LlmUsageService llmUsageService) {
        this.llmUsageService = llmUsageService;
    }

    @GetMapping
    @Operation(summary = "Get the workspace's LLM usage report for one month", operationId = "getLlmUsageReport")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<WorkspaceLlmUsageReportDTO> getReport(
            WorkspaceContext workspaceContext,
            @RequestParam(required = false)
                    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "month must be ISO yyyy-MM")
                    @Nullable
                    String month) {
        YearMonth target = month != null ? YearMonth.parse(month) : YearMonth.now(ZoneOffset.UTC);
        return ResponseEntity.ok(llmUsageService.getWorkspaceReport(workspaceContext.id(), target));
    }
}
