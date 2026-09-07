package de.tum.cit.aet.hephaestus.achievement.progress;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Subtype names are stored in existing JSONB payloads. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LinearAchievementProgress.class, name = "LinearAchievementProgress"),
    @JsonSubTypes.Type(value = BinaryAchievementProgress.class, name = "BinaryAchievementProgress"),
})
public interface AchievementProgress {}
