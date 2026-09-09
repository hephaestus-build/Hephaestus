package de.tum.cit.aet.hephaestus.workspace.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Workspace policy acknowledgements are not the account's optional research consent. */
public record WorkspaceAccessPolicySettings(
        @NonNull @NotBlank @Size(max = 64) String primaryRegistrationId,
        @NonNull @NotBlank @Size(max = 20000) String introductionMarkdown,
        @NonNull @NotBlank @Size(max = 300) String acknowledgementLabel,
        @NonNull @NotNull @Size(max = 8) List<@Valid LinkRequirementDTO> requiredLinks,
        @NonNull @NotNull @Size(max = 8) List<@Valid PolicyNoticeDTO> notices,
        @NonNull @NotNull @Positive Long maintainerTeamId,
        @NonNull @NotNull @Size(max = 100) List<@NotNull @Positive Long> requestableTeamIds,
        @Min(1) @Max(3650) int maximumDurationDays,
        @Min(1) @Max(365) int reminderDays,
        @NonNull @NotBlank @Email @Size(max = 320) String adminMailbox,
        @Nullable @Min(1) @Max(3650) Integer personalDataRetentionDays) {
    public record LinkRequirementDTO(
            @NonNull @NotBlank @Size(max = 64) String registrationId,
            @Nullable @Size(max = 128) String teamId) {}

    public record PolicyNoticeDTO(
            @NonNull @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}")
            String key,

            @NonNull @NotBlank @Size(max = 200) String title,
            @NonNull @NotBlank @Size(max = 20000) String markdown) {}
}
