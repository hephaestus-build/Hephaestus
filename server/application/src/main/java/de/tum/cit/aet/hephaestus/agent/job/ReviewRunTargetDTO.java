package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunTargetRow;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
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

        @Nullable IntegrationKind provider,
        @NonNull String title) {
    static ReviewRunTargetDTO from(AgentJob job) {
        return from(ReviewRunTargetMapper.from(job));
    }

    static ReviewRunTargetDTO from(ReviewRunTargetRow row) {
        return from(ReviewRunTargetMapper.from(row));
    }

    private static ReviewRunTargetDTO from(Target target) {
        Long id = target.id();
        return new ReviewRunTargetDTO(
                target.type(),
                id == null ? null : ReviewedWorkLabels.ref(target.type(), id, target),
                target.provider(),
                target.title());
    }
}
