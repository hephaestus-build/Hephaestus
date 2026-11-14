package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's model + execution limits for one agent purpose and data-handling tier, plus whether
 * it resolves to an available model right now.
 */
@Schema(description = "A workspace's model assignment for one purpose")
public record ModelAssignmentDTO(
        @NonNull ModelPurpose purpose,
        @NonNull DataHandlingTier dataHandlingTier,
        @Nullable Long instanceModelId,
        @Nullable Long workspaceModelId,
        int timeoutSeconds,
        @NonNull Boolean enabled,

        @NonNull @Schema(description = "True when the bound model is available to run right now")
        Boolean ready,

        @NonNull
        @Schema(
                description = "The members this binding serves now, named by the tier of their choice: IN_HOUSE and "
                        + "CLOUD for the members who chose them, UNDECLARED for the members who have not chosen. "
                        + "Empty when the binding serves no member.")
        List<DataHandlingTier> servedTiers) {
    public static ModelAssignmentDTO from(
            WorkspaceModelAssignment binding, boolean ready, List<DataHandlingTier> servedTiers) {
        return new ModelAssignmentDTO(
                binding.getPurpose(),
                binding.getDataHandlingTier(),
                binding.getInstanceModel() == null
                        ? null
                        : binding.getInstanceModel().getId(),
                binding.getWorkspaceModel() == null
                        ? null
                        : binding.getWorkspaceModel().getId(),
                // The timeout that applies: a Hephaestus turn stops at its ceiling whatever an older binding stored.
                binding.getPurpose() == ModelPurpose.CONVERSATION
                        ? Math.min(binding.getTimeoutSeconds(), ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS)
                        : binding.getTimeoutSeconds(),
                binding.isEnabled(),
                ready,
                List.copyOf(servedTiers));
    }
}
