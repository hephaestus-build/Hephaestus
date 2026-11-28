package de.tum.cit.aet.hephaestus.practices.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether one side of a review differs from its baseline. Only the evidence can be unknown.")
public enum ReviewDifference {
    SAME,
    CHANGED,
    UNKNOWN;
}
