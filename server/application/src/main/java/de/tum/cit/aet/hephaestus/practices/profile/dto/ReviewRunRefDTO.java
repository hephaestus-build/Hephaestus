package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One review run on the developer's work")
public record ReviewRunRefDTO(
        @NonNull UUID reviewId,

        @NonNull @Schema(description = "When the review recorded its newest observation about this developer")
        Instant at,

        @NonNull @Schema(description = "The piece of work the run reviewed")
        ReviewedWorkRefDTO reviewedWork,

        @Nullable @Schema(description = "Where the review stands; absent when the review itself is no longer on record")
        ReviewRunState status) {}
