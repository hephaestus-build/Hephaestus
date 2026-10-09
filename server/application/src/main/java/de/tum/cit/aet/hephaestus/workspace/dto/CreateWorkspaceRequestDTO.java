package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.validation.ScmServerUrl;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlug;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @WorkspaceSlug
        @Schema(
                description = "Non-reserved lowercase ASCII DNS label. Consecutive hyphens are prohibited.",
                pattern = WorkspaceSlugValidator.LABEL_PATTERN,
                minLength = 1,
                maxLength = WorkspaceSlugValidator.MAX_LENGTH,
                example = "my-workspace")
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

        @NotNull(message = "Account type is required") @Schema(description = "Type of account (USER or ORG)")
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
                        "For GitLab, the default GitLab instance this server reads from, which is also used when omitted; any other instance is refused.",
                example = "https://gitlab.example.com")
        @Nullable
        @ScmServerUrl
        String serverUrl) {
    @Override
    @Deprecated(forRemoval = true)
    public @Nullable Long ownerUserId() {
        return ownerUserId;
    }

    @AssertTrue(message = "Personal access token is required")
    @Schema(hidden = true)
    private boolean isTokenProvided() {
        return personalAccessToken != null && !personalAccessToken.isBlank();
    }

    @AssertTrue(message = "kind must be GITHUB or GITLAB. Use OAuth to create a SLACK workspace.")
    @Schema(hidden = true)
    private boolean isKindSupported() {
        return kind == IntegrationKind.GITHUB || kind == IntegrationKind.GITLAB;
    }
}
