package de.tum.cit.aet.hephaestus.practices.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "What one run decided about each practice it observed for this developer against the"
                + " practice's whole positive standard, one count per practice however many observations it"
                + " recorded; an invalidated observation decides nothing, and a met or not-met outcome recorded"
                + " under an earlier or missing assessment scheme counts as undetermined")
public record ReviewPracticeOutcomesDTO(
        @NonNull @Schema(description = "Practices with at least one counted not-met outcome")
        Integer notMet,

        @NonNull @Schema(description = "Practices with at least one counted met outcome and none not met")
        Integer met,

        @NonNull @Schema(description = "Practices whose every counted outcome was not applicable")
        Integer notApplicable,

        @NonNull
        @Schema(
                description = "Practices the run could not settle: no counted met or not-met outcome, and not"
                        + " only not-applicable outcomes")
        Integer undetermined) {}
