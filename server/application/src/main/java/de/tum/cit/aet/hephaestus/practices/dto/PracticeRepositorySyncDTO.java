package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * The report of a sync of the practice repository from its default branch (ADR 0118).
 *
 * @param commit the commit at the head of the default branch that the sync read
 * @param practices each folder, and each practice edited in the repository whose folder is gone, by slug
 * @param problems why the directory of the practice folders could not be read. The sync then changed nothing.
 */
public record PracticeRepositorySyncDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String commit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant syncedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PracticeSyncResultDTO> practices,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PracticeFileProblemDTO> problems) {}
