package de.tum.cit.aet.hephaestus.workspace.onboarding;

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
        @NonNull boolean enabled,
        @NonNull boolean aiChoiceRequired,
        @NonNull long revision,
        @NonNull @NotNull @Size(max = 20) List<@NotNull @Positive Long> requiredConnectionIds) {}
