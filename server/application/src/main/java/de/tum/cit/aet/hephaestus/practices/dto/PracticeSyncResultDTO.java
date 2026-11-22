package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a sync of the practice repository did with one folder, or with a practice whose folder is gone.
 *
 * @param slug the folder's name, which is the practice's slug
 * @param problems why the folder is not valid; empty for every other status
 */
public record PracticeSyncResultDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PracticeSyncStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PracticeFileProblemDTO> problems) {}
