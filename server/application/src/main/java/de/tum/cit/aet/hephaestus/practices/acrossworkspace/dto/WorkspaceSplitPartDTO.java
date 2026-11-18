package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "The developers at one verdict in a split")
public record WorkspaceSplitPartDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The verdict the part counts")
        Verdict standing,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Developers at the verdict, the reader included when counted. Null when the split"
                        + " shows only its total")
        @Nullable
        Integer developers) {}
