package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A request for one review by a review setup (ADR 0167 decision 1). It names no audience, budget, origin or requester:
 * the setup gives the audience and the budget, the route gives the origin, and the signed-in account is the
 * requester. The same key returns the same review; the same key with another input is refused.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        UUID setupId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
        ReviewRequestItemDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        UUID requestKey) {}
