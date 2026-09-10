package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Job history for a workspace's agents. The literal {@code jobs} segment collides with the sibling
 * {@code /agents/{purpose}} template; Spring resolves it here because a literal segment outranks a
 * variable one.
 */
@WorkspaceScopedController
@RequestMapping("/agents/jobs")
@Tag(name = "Agent Jobs", description = "Workspace-scoped agent job monitoring")
@RequiredArgsConstructor
@Validated
public class AgentJobController {

    private final AgentJobService agentJobService;
    private final AgentJobLifecycleService agentJobLifecycleService;

    @GetMapping
    @Operation(summary = "List agent jobs for a workspace")
    @ApiResponse(responseCode = "200", description = "Paginated job list")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<Page<AgentJobDTO>> listAgentJobs(
            WorkspaceContext workspaceContext,
            @Parameter(description = "Filter by job status") @RequestParam(required = false) AgentJobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int pageSize = Math.max(1, Math.min(size, 100));
        Pageable pageable =
                PageRequest.of(safePage, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AgentJobDTO> jobs =
                agentJobService.getJobs(workspaceContext.id(), status, pageable).map(AgentJobDTO::from);
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get agent job details")
    @ApiResponse(
            responseCode = "200",
            description = "Job detail returned",
            content = @Content(schema = @Schema(implementation = AgentJobDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Job not found in this workspace",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<AgentJobDTO> getAgentJob(WorkspaceContext workspaceContext, @PathVariable UUID jobId) {
        AgentJob job = agentJobService.getJob(workspaceContext.id(), jobId);
        return ResponseEntity.ok(AgentJobDTO.from(job));
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(summary = "Cancel an agent job")
    @ApiResponse(responseCode = "200", description = "Job cancelled")
    @ApiResponse(
            responseCode = "404",
            description = "Job not found in this workspace",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Job already in terminal state",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "job control, not configuration; job state is its own record")
    public ResponseEntity<AgentJobDTO> cancelAgentJob(WorkspaceContext workspaceContext, @PathVariable UUID jobId) {
        AgentJob job = agentJobLifecycleService.cancel(workspaceContext.id(), jobId);
        return ResponseEntity.ok(AgentJobDTO.from(job));
    }

    @PostMapping("/{jobId}/delivery/retry")
    @Operation(summary = "Retry delivery for a completed agent job")
    @ApiResponse(responseCode = "200", description = "Delivery retried")
    @ApiResponse(
            responseCode = "404",
            description = "Job not found in this workspace",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Job not in a retryable state",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "job control, not configuration; job state is its own record")
    public ResponseEntity<AgentJobDTO> retryAgentJobDelivery(
            WorkspaceContext workspaceContext, @PathVariable UUID jobId) {
        AgentJob job = agentJobLifecycleService.retryDelivery(workspaceContext.id(), jobId);
        return ResponseEntity.ok(AgentJobDTO.from(job));
    }
}
