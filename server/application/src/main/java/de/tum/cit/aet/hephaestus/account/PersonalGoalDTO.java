package de.tum.cit.aet.hephaestus.account;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The goal in the signed-in account's own words, with the revision of the goal slot. */
public record PersonalGoalDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Nullable String words) {
    static PersonalGoalDTO of(PersonalGoal goal) {
        return new PersonalGoalDTO(goal.getRevision(), goal.getWords());
    }
}
