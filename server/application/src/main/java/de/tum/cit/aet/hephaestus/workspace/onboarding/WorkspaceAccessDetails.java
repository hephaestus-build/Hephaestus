package de.tum.cit.aet.hephaestus.workspace.onboarding;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Every approval has an absolute end; a pending renewal never moves it. */
public record WorkspaceAccessDetails(
        @NonNull @NotNull @Positive Long maintainerAccountId,
        @NonNull @NotNull @Size(max = 100) List<@NotNull @Positive Long> teamIds,
        @NonNull @NotNull Instant expiresAt) {}
