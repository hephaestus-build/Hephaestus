package de.tum.cit.aet.hephaestus.practices.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/** A practice one run recorded a problem about, named so a row can lead with what slipped. */
@Schema(description = "A practice a run recorded a problem about for this developer")
public record SlippedPracticeDTO(
        @NonNull String practiceSlug, @NonNull String practiceName) {}
