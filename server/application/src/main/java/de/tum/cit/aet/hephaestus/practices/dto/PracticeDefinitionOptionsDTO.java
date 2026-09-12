package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "What a practice author may choose, per type of reviewed work")
public record PracticeDefinitionOptionsDTO(
        /**
         * The evidence facts below — what a source's capture covers, and whether it can be captured whole —
         * are properties of one contract version. A practice states the version it was written against
         * (in its automated-review validation), so saying it here too is what lets a reader tell "these
         * options describe this practice" from "these options describe a newer contract".
         */
        @NonNull
        @Schema(
                description = "Source contract these options describe",
                example = ArtifactSourceCatalogRegistry.CURRENT_VERSION_VALUE)
        SourceContractVersion sourceContractVersion,

        @NonNull List<PracticeWorkTypeDefinitionOptionsDTO> workTypes) {}
