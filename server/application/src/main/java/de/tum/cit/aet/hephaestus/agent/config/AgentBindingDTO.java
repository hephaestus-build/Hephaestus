package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmProcessingLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's model + execution limits for one agent purpose, plus whether it resolves to
 * an available model right now.
 */
@Schema(description = "A workspace's agent binding for one purpose")
public record AgentBindingDTO(
        @NonNull AgentPurpose purpose,
        @NonNull LlmProcessingLocation processingLocation,
        @Nullable Long instanceModelId,
        @Nullable Long workspaceModelId,
        int timeoutSeconds,
        int maxConcurrentJobs,
        boolean allowInternet,
        @NonNull Boolean enabled,

        @NonNull @Schema(description = "True when the bound model is available to run right now")
        Boolean ready) {
    public static AgentBindingDTO from(WorkspaceAgentBinding binding, boolean ready) {
        return new AgentBindingDTO(
                binding.getPurpose(),
                binding.getProcessingLocation(),
                binding.getInstanceModel() == null
                        ? null
                        : binding.getInstanceModel().getId(),
                binding.getWorkspaceModel() == null
                        ? null
                        : binding.getWorkspaceModel().getId(),
                binding.getTimeoutSeconds(),
                binding.getMaxConcurrentJobs(),
                binding.isAllowInternet(),
                binding.isEnabled(),
                ready);
    }
}
