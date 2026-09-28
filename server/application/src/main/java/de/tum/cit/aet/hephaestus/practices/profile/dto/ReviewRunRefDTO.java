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

        @NonNull
        @Schema(
                description = "When the run stopped, once it has stopped, and when it began while it is still"
                        + " going. A run whose own start and end were never recorded falls back to when it wrote"
                        + " its newest observation.")
        Instant at,

        @NonNull @Schema(description = "The piece of work the run reviewed")
        ReviewedWorkRefDTO reviewedWork,

        @Nullable
        @Schema(
                description = "How the run ended, so the page can say a review is still going or that nothing came"
                        + " of it; absent when the run itself is no longer on record")
        ReviewRunState status) {}
