package de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * One piece of work that came back clean on the practice after the feedback was prepared.
 *
 * <p>It carries its review date for the same reason evidence does: the card's strip reads left to right
 * in time, and a piece without a date could only be placed by its kind. Nothing else travels — a clean
 * piece has no summary to show and its outcome is what being here says.
 */
@Schema(description = "One piece of work that came back clean on the practice since the feedback was prepared")
public record InAppCleanWorkDTO(
        @NonNull @Schema(description = "The piece of work, as every surface names it")
        ReviewedWorkRefDTO reviewedWork,

        @NonNull @Schema(description = "When that piece was reviewed")
        Instant reviewedAt) {}
