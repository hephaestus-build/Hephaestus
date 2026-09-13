package de.tum.cit.aet.hephaestus.productfeedback;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** Counts refer to email requests, not in-app invitations or responses. Accepted means SMTP relay acceptance. */
public record SurveyEmailInvitationSummaryDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) int eligible,
        @Schema(requiredMode = RequiredMode.REQUIRED) int alreadyRequested,
        @Schema(requiredMode = RequiredMode.REQUIRED) int accepted,
        @Schema(requiredMode = RequiredMode.REQUIRED) int queued,
        @Schema(requiredMode = RequiredMode.REQUIRED) int remaining) {}
