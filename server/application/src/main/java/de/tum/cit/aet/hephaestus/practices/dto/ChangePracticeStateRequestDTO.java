package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** The state to move a draft practice to. Only {@code ACTIVE} is a change a draft can make. */
public record ChangePracticeStateRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        PracticeState state) {}
