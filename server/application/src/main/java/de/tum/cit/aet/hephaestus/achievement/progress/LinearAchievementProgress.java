package de.tum.cit.aet.hephaestus.achievement.progress;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record LinearAchievementProgress(
        @PositiveOrZero @JsonProperty int current,

        @Positive @JsonProperty(required = true) int target) implements AchievementProgress {}
