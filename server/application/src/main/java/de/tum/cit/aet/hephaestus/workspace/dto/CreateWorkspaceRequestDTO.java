package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.core.security.ServerUrlValidator;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * DTO for creating a new workspace.
 *
 * <p>Supports PAT-backed GitHub and GitLab workspaces. {@code kind} discriminates;
 * {@code personalAccessToken} carries the PAT (encrypted at rest by the registry);
 * {@code serverUrl} is optional for self-hosted instances. GitHub App workspaces are
 * provisioned automatically by the lifecycle listener, not via this endpoint.
 */
@Schema(description = "Request to create a new workspace")
public record CreateWorkspaceRequestDTO(
        @NotBlank(message = "Workspace slug is required")
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9-]{2,50}$",
                message =
                        "Slug must be 3-51 characters, start with a lowercase letter or digit, and contain only lowercase letters, digits, or hyphens")
        @Schema(description = "URL-friendly identifier for the workspace", example = "my-workspace")
        @Nullable
        String workspaceSlug,

        @NotBlank(message = "Display name is required")
        @Size(max = 120, message = "Display name must not exceed 120 characters")
        @Schema(description = "Human-readable name of the workspace", example = "My Workspace")
        @Nullable
        String displayName,

        @NotBlank(message = "Account login is required")
        @Size(max = 255, message = "Account login must not exceed 255 characters")
        @Schema(description = "Git provider account login (GitHub org/user or GitLab group path)", example = "my-org")
        @Nullable
        String accountLogin,

        @NotNull(message = "Account type is required") @Schema(description = "Type of account (USER or ORG)") @Nullable
        AccountType accountType,

        @Schema(
                description = "Deprecated: ignored by the server. The authenticated user always becomes the owner.",
                deprecated = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        Long ownerUserId,

        @NotNull(message = "Integration kind is required")
        @Schema(
                description = "Integration kind to provision. SLACK flows through OAuth, not this endpoint.",
                allowableValues = {"GITHUB", "GITLAB"},
                example = "GITLAB")
        @Nullable
        IntegrationKind kind,

        @Size(max = 512, message = "Personal access token must not exceed 512 characters")
        @Schema(
                description =
                        "Personal Access Token. Required for both kinds (GitLab API or GitHub PAT). Stored encrypted at rest.",
                example = "glpat-...")
        @Nullable
        String personalAccessToken,

        @Schema(
                description =
                        "Custom server URL for self-hosted GitLab instances. Must use HTTPS. Defaults to https://gitlab.com if not specified.",
                example = "https://gitlab.example.com")
        @Nullable
        String serverUrl) {
    @Deprecated(forRemoval = true)
    public @Nullable Long ownerUserId() {
        return ownerUserId;
    }

    @AssertTrue(message = "Personal access token is required")
    @Schema(hidden = true)
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isTokenProvided() {
        return personalAccessToken != null && !personalAccessToken.isBlank();
    }

    @AssertTrue(message = "kind must be GITHUB or GITLAB; SLACK flows through OAuth")
    @Schema(hidden = true)
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isKindSupported() {
        return kind == IntegrationKind.GITHUB || kind == IntegrationKind.GITLAB;
    }

    @AssertTrue(message = "Server URL must use HTTPS and must not point to private/reserved addresses")
    @Schema(hidden = true)
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isServerUrlSafe() {
        if (serverUrl == null || serverUrl.isBlank()) {
            return true;
        }
        try {
            ServerUrlValidator.validate(serverUrl);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
