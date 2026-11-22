package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a sync of the practice repository did with one folder, or with a practice whose folder is gone.
 *
 * @param slug the folder's name, which is the practice's slug
 * @param problems why the folder is not valid, or why its practice cannot be retired; empty for every other status
 * @param untried whether the sync made a new current version whose review digest no trial of the practice ran to the
 *     end. Only a created, taken-over or updated practice can be untried.
 */
public record PracticeSyncResultDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PracticeSyncStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PracticeFileProblemDTO> problems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean untried) {}
