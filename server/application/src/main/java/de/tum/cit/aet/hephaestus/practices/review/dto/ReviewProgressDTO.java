package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How far an active review got. A review on a slow provider can take many attempts, each ending some of its
 * practices, and between them its job waits as it would for a first start. This count is what tells the two apart.
 * It is not a result: an attempt keeps what it ended private until the last attempt admits it (ADR 0063).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "How far an active item review got; absent for a scan, which lists its items in pages")
public record ReviewProgressDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Practices that an attempt ended, with a result or a recorded reason. Their"
                        + " results stay private until the review's last attempt admits them.")
        int practicesEnded,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Practices that the review's attempts work on. A practice decided before the first"
                        + " attempt, such as one withheld for readiness, is not counted.")
        int practicesTotal,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description =
                        "An attempt ended some of the practices, and the review waits to continue with the" + " rest")
        boolean continuing) {}
