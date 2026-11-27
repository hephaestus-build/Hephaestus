package de.tum.cit.aet.hephaestus.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SavePersonalGoalDTO(
        @NotNull @PositiveOrZero Long revision,
        @NotBlank @Size(max = PersonalGoal.MAX_WORDS_LENGTH) String words) {}
