package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Where the practice is edited from now on: Move to repository, or Edit in Hephaestus. */
public record ChangePracticeEditedInRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        PracticeEditedIn editedIn) {}
