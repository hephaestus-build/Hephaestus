package de.tum.cit.aet.hephaestus.practices.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "What one run decided about each practice it observed for this developer, one outcome per"
                + " practice however many observations it recorded about it; an invalidated observation decides"
                + " nothing")
public record ReviewPracticeOutcomesDTO(
        @NonNull @Schema(description = "Practices with at least one problem observed")
        Integer toImprove,

        @NonNull @Schema(description = "Practices with a strength observed and no problem")
        Integer held,

        @NonNull @Schema(description = "Practices whose every observation said the practice did not apply to this work")
        Integer notApplicable,

        @NonNull
        @Schema(
                description = "Practices the run looked at and could not settle either way: no strength, no problem,"
                        + " and not only a verdict that the practice did not apply")
        Integer undecided) {}
