package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The workspace's practice repository (ADR 0118).
 *
 * @param nameWithOwner the repository's provider path; null when it is no longer monitored
 * @param defaultBranch the branch whose head holds the practices; null until the provider has named it
 */
public record PracticeRepositoryDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string") String repositoryItemId,
        @Nullable String nameWithOwner,
        @Nullable String defaultBranch,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String directory) {}
