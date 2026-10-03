package de.tum.cit.aet.hephaestus.workspace.onboarding;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * {@code aiChoiceRequired} is read on GET and ignored on PUT: the server latches it the first time
 * the setup page is enabled and never clears it.
 */
public record WorkspaceOnboardingSettingsDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean aiChoiceRequired,
        @Schema(requiredMode = RequiredMode.REQUIRED) long revision,
        @NonNull @NotNull @Size(max = 20) List<@NotNull @Positive Long> requiredConnectionIds) {}
