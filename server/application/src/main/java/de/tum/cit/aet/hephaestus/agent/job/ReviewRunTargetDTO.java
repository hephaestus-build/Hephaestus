package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Work reviewed by an agent job")
public record ReviewRunTargetDTO(
        @NonNull ArtifactKind type,

        @Schema(description = "The reviewed work as every surface names it; absent when the run recorded no work")
        @Nullable
        ReviewedWorkRefDTO reviewedWork,

        @NonNull @Schema(description = "Heading the run is listed under, which a run without recorded work still needs")
        String title) {
    static ReviewRunTargetDTO from(Target target) {
        return new ReviewRunTargetDTO(
                target.type(), ReviewedWorkLabels.refOrNull(target.type(), target.id(), target), target.title());
    }
}
