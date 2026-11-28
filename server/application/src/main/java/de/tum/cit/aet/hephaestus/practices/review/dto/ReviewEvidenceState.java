package de.tum.cit.aet.hephaestus.practices.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description =
                "What the server read for one evidence source when it created the review. A missing or incomplete source"
                        + " leaves its criteria Could not determine.")
public enum ReviewEvidenceState {
    PRESENT,
    INCOMPLETE,
    MISSING;
}
