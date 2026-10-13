package de.tum.cit.aet.hephaestus.agent.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;

/**
 * Set a workspace's model + execution limits for one agent purpose. Exactly one of
 * {@code instanceModelId} / {@code workspaceModelId} must be provided. Execution limits and the
 * enabled flag keep their current value when omitted.
 */
@Schema(description = "Bind a model and execution limits to an agent purpose")
public record AgentBindingRequestDTO(
        @Nullable @Schema(description = "Shared (instance-catalog) model id to run this purpose on")
        Long instanceModelId,

        @Nullable @Schema(description = "Workspace-owned model id to run this purpose on")
        Long workspaceModelId,

        @Nullable
        @Min(AgentBindingLimits.MIN_TIMEOUT_SECONDS)
        @Max(AgentBindingLimits.MAX_TIMEOUT_SECONDS)
        @Schema(description = "Per-run timeout in seconds (30–10800; three hours is the longest a single run may take)")
        Integer timeoutSeconds,

        @Nullable @Min(1) @Schema(description = "Maximum concurrent runs for this purpose")
        Integer maxConcurrentJobs,

        @Nullable @Schema(description = "Whether this purpose is active (paused when false)")
        Boolean enabled) {}
