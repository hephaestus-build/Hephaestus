package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewQueueDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long running,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long waiting,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant oldestWaitingSince,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long heldForBudget,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewQueueItemDTO> head,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long finishedLastHour,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long finishedLastDay,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double finishedPerHourLastDay) {
    public ReviewQueueDTO {
        head = List.copyOf(head);
    }
}
