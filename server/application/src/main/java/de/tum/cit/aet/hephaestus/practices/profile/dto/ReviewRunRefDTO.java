package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(description = "One review run on the developer's work")
public record ReviewRunRefDTO(
        @NonNull UUID jobId,

        @NonNull @Schema(description = "When the run recorded its newest observation")
        Instant at,

        @NonNull @Schema(description = "The piece of work the run reviewed")
        ReviewedWorkRefDTO reviewedWork) {}
