package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.practices.spi.ApplicabilityClauseKind;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeItemTypeOptionDTO(
        String kind,
        String label,
        List<ItemEventOptionDTO> events,
        List<AboutRole> aboutRoles,
        List<ReadSourceOptionDTO> readSources,
        List<StateDimensionDTO> reviewOnStateDimensions,
        boolean scanSupported,
        List<String> scanItemKinds,
        List<ApplicabilityClauseKind> applicabilityClauses,
        List<ReadSourceOptionDTO> applicabilitySources,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description = "Whether feedback can appear on the reviewed work itself. Otherwise feedback about this"
                        + " kind of item goes only to the author's practice page and conversation.")
        boolean feedbackOnWork) {
    public PracticeItemTypeOptionDTO {
        events = List.copyOf(events);
        aboutRoles = List.copyOf(aboutRoles);
        readSources = List.copyOf(readSources);
        reviewOnStateDimensions = List.copyOf(reviewOnStateDimensions);
        scanItemKinds = List.copyOf(scanItemKinds);
        applicabilityClauses = List.copyOf(applicabilityClauses);
        applicabilitySources = List.copyOf(applicabilitySources);
    }
}
