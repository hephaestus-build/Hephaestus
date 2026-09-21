package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The practice profile's overview for the calling developer.
 *
 * <p>Every change is measured over {@link #window}. It opens after the previous run and closes now, not at
 * {@code latestRun.at}: the latest run is the newest observation on record, but the feedback it composed
 * lands moments after that observation and the developer's own responses land whenever they respond, and
 * both are "since the latest run" in the only sense a reader means.
 */
@Schema(description = "What held, what changed and which work was reviewed over a window of the developer's reviews")
public record PracticeProfileOverviewDTO(
        @NonNull @Schema(description = "The span every change below was measured over")
        OverviewWindowDTO window,

        @Nullable @Schema(description = "The newest review run on the developer's work; absent before any ran")
        ReviewRunRefDTO latestRun,

        @NonNull
        @Schema(
                description =
                        "Practices standing as a strength whose newest pieces of work are all clean, longest run first")
        List<HeldPracticeDTO> holdingUp,

        @NonNull
        @Schema(description = "Everything that changed in the window, newest first; one change per practice and type")
        List<ProfileChangeDTO> changes,

        @NonNull @Schema(description = "The pieces of work whose runs fall in the window, deduplicated, newest first")
        List<ReviewedWorkRefDTO> reviewedWork) {}
