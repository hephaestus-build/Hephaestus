package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

public record GitHubInstallationClaimRequestDTO(
        @Schema(description = "The code that GitHub returned to the setup page; it is used once and never stored")
        @NotBlank
        @Size(max = 255)
        @NonNull
        String code) {}
