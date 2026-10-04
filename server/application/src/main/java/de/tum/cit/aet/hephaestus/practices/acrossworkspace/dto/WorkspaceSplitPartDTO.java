package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "The developers at one verdict in a split")
public record WorkspaceSplitPartDTO(
        @NonNull @Schema(description = "The verdict the part counts")
        Verdict standing,

        @NonNull @Schema(description = "Developers at the verdict, the reader included when counted")
        Integer developers) {}
