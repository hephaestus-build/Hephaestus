package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * One line in the index of everything this workspace was in a position to say something about.
 *
 * <p>Built from the signal ledger rather than from reviews, which is the point: an index of runs can
 * only list work that was reviewed, and the work that was <em>not</em> is what somebody comes here to
 * ask about.
 */
@Schema(description = "An artifact this workspace recorded something about, and how much of it turned into review")
public record TracedArtifactDTO(
        @NonNull ArtifactKind artifactKind,
        @NonNull Long artifactId,

        @NonNull
        @Schema(
                description = "The work as every surface names it: its provider, the label the provider writes "
                        + "(\"#1423\", \"!1423\"), its title, where it sits and where to open it; the link is "
                        + "absent for deleted work")
        ReviewedWorkRefDTO reviewedWork,

        @NonNull Instant lastSignalAt,

        @NonNull @Schema(description = "Occurrences recorded on this artifact")
        Integer signalCount,

        @NonNull @Schema(description = "How many of them started a review")
        Integer reviewedSignalCount) {}
