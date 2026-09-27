package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(
        description =
                "The caller's in-context feedback with recorded provider comments on one authorized piece of work; no feedback bodies")
public record DeliveredWorkFeedbackDTO(
        @NonNull ReviewedWorkRefDTO work,
        @NonNull List<DeliveredWorkFeedbackItemDTO> feedback,

        @NonNull @Schema(description = "Older delivered feedback exists beyond this bounded view")
        Boolean hasMore) {}
