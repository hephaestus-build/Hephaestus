package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The result of checking a practice folder. Nothing was saved, and no model was called.")
public record PracticeFileCheckDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether the folder is a valid practice")
        boolean valid,

        @Schema(description = "The practice name, when the folder is valid") @Nullable
        String name,

        @Schema(description = "The item kind that the practice reviews, when the folder is valid") @Nullable
        String kind,

        @Schema(description = "The group slug that the folder names, when the folder is valid and names one") @Nullable
        String groupSlug,

        @Schema(description = "Why the folder cannot become a practice. Empty when it is valid")
        List<PracticeFileProblemDTO> problems) {}
