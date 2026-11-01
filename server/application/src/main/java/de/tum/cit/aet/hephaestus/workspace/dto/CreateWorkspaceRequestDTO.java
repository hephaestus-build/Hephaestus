package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.validation.ScmServerUrl;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlug;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * DTO for creating a new workspace.
 *
 * <p>Supports PAT-backed GitHub and GitLab workspaces. {@code kind} discriminates;
 * {@code personalAccessToken} carries the PAT (encrypted at rest by the registry);
 * {@code serverUrl} is optional for self-hosted instances. GitHub App workspaces are
 * provisioned automatically by the lifecycle listener, not via this endpoint. A GitLab workspace
 * monitors every project of its group unless {@code repositorySelection} is {@code SELECTED}.
 */
@Schema(description = "Request to create a new workspace")
public record CreateWorkspaceRequestDTO(
        @NotBlank(message = "Workspace slug is required")
        @WorkspaceSlug
        @Schema(
                description = "Non-reserved lowercase ASCII DNS label. Consecutive hyphens are prohibited.",
                pattern = WorkspaceSlugValidator.LABEL_PATTERN,
                minLength = WorkspaceSlugValidator.MIN_LENGTH,
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
        String serverUrl,

        @Schema(
                description =
                        "GitLab only. ALL monitors every project of the group, including ones created later; SELECTED monitors only selectedProjectIds. Omitted means ALL.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        RepositorySelection repositorySelection,

        @Schema(
                description =
                        "GitLab project IDs to monitor. Required, and only allowed, when repositorySelection is SELECTED. The server checks each with the token.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        List<@NotNull @Positive Long> selectedProjectIds) {
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

    @AssertTrue(
            message = "Choose all projects without project IDs, or choose SELECTED with at least one project ID."
                    + " Only GitLab workspaces choose projects.")
    @Schema(hidden = true)
    private boolean isRepositorySelectionConsistent() {
        return hasConsistentRepositorySelection();
    }

    /**
     * Whether the selection fields agree: a GitHub request sends neither, a GitLab request sends project IDs exactly
     * when it chooses {@code SELECTED}, and every ID is positive. Workspace creation applies this rule to in-process
     * callers too, which bean validation does not reach.
     */
    public boolean hasConsistentRepositorySelection() {
        if (kind != IntegrationKind.GITLAB) {
            return repositorySelection == null && selectedProjectIds == null;
        }
        if (repositorySelection != RepositorySelection.SELECTED) {
            return selectedProjectIds == null;
        }
        return selectedProjectIds != null
                && !selectedProjectIds.isEmpty()
                && selectedProjectIds.stream().allMatch(id -> id != null && id > 0);
    }
}
