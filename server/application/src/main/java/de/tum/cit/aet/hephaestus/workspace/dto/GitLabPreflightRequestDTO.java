package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.validation.ScmServerUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for GitLab pre-creation checks (token validation and group listing).
 *
 * <p>The PAT is sent in the request body (not a header) because this is a POST
 * endpoint and the body is encrypted in transit via TLS.
 */
@Schema(description = "Request to validate a GitLab PAT or list accessible groups before workspace creation")
public record GitLabPreflightRequestDTO(
        @NotBlank(message = "Personal access token is required")
        @Schema(description = "GitLab Personal Access Token to validate", example = "your-gitlab-token")
        String personalAccessToken,

        @Schema(
                description =
                        "The default GitLab instance this server reads from, which is also used when omitted; any other instance is refused.",
                example = "https://gitlab.example.com")
        @ScmServerUrl
        @Nullable
        String serverUrl,

        @Schema(
                description =
                        "GitLab group full path, used to validate the token against the group when /api/v4/user refuses it",
                example = "my-org/my-team")
        @Nullable
        String groupFullPath) {}
