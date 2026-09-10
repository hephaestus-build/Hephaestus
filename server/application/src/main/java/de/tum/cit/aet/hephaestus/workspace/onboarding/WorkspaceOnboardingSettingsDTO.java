package de.tum.cit.aet.hephaestus.workspace.onboarding;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.NonNull;

public record WorkspaceOnboardingSettingsDTO(
        @NonNull boolean enabled,
        @NonNull long revision,
        @NonNull @NotNull @Size(max = 20000) String welcomeMarkdown,
        @NonNull @NotNull @Size(max = 20) List<@NotNull @Positive Long> requiredConnectionIds) {}
