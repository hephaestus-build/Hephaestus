package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionMerge;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

public record PracticeReleaseFieldDTO(
        @NonNull PracticeDefinitionField field,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean offeredChanged,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean conflict) {
    public static PracticeReleaseFieldDTO from(PracticeDefinitionMerge.FieldChange change) {
        return new PracticeReleaseFieldDTO(change.field(), change.offeredChanged(), change.conflict());
    }
}
