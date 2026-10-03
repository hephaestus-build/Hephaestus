package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for GitLab PAT pre-creation validation.
 */
@Schema(description = "Result of GitLab PAT validation")
public record GitLabPreflightResponseDTO(
        @Schema(description = "Whether the token is valid", requiredMode = RequiredMode.REQUIRED)
        boolean valid,

        @Nullable @Schema(description = "Username of the token owner (personal tokens) or group name (group tokens)")
        String username,

        @Nullable @Schema(description = "GitLab user ID or group ID")
        Long userId,

        @Nullable @Schema(description = "Error message if validation failed")
        String error) {
    public static GitLabPreflightResponseDTO success(String username, Long userId) {
        return new GitLabPreflightResponseDTO(true, username, userId, null);
    }

    public static GitLabPreflightResponseDTO failure(String error) {
        return new GitLabPreflightResponseDTO(false, null, null, error);
    }
}
