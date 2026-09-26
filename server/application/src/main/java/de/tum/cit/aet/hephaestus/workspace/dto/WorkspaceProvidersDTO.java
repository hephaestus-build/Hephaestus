package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "Available workspace creation providers and their configuration")
public record WorkspaceProvidersDTO(
        @Schema(description = "GitHub workspace provider config, null if not available") @Nullable
        GitHubProviderDTO github,

        @Schema(description = "GitLab workspace provider config, null if not available") @Nullable
        GitLabProviderDTO gitlab,

        @Schema(description = "Who may create workspaces — ADMIN_ONLY restricts creation to instance admins")
        WorkspaceProperties.CreationPolicy creationPolicy) {
    @Schema(description = "GitHub provider configuration")
    public record GitHubProviderDTO(
            @Schema(
                    description = "GitHub App installation URL",
                    example = "https://github.com/apps/hephaestus/installations/new")
            String appInstallationUrl) {}

    @Schema(description = "GitLab provider configuration")
    public record GitLabProviderDTO(
            @Schema(
                    description = "Origin of the one GitLab instance workspaces are created on",
                    example = "https://gitlab.lrz.de")
            String defaultServerUrl) {}
}
