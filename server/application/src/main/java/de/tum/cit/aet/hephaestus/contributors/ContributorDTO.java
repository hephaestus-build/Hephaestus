package de.tum.cit.aet.hephaestus.contributors;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * Data transfer object representing a GitHub contributor.
 * Used to display contributor information on the public about page.
 */
@Schema(description = "Information about a contributor to the Hephaestus project")
public record ContributorDTO(
        @NonNull @Schema(description = "GitHub user ID") Long id,
        @NonNull @Schema(description = "GitHub username") String login,

        @NonNull @Schema(description = "Display name of the contributor")
        String name,

        @NonNull @Schema(description = "URL to the contributor's avatar image")
        String avatarUrl,

        @NonNull @Schema(description = "URL to the contributor's GitHub profile")
        String htmlUrl,

        @Schema(description = "Number of contributions to the project", example = "42")
        int contributions) {}
