package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The whole answer to "what did Hephaestus do about this piece of work, and why".
 *
 * <p>Every practice the workspace runs against this kind of work appears, including — especially — the
 * ones that did nothing. A listing of only what happened cannot answer the question the view is for.
 */
@Schema(description = "Every practice's answer for one artifact, and the occurrences those answers rest on")
public record ArtifactTraceDTO(
        @NonNull ArtifactKind artifactKind,
        @NonNull Long artifactId,

        @NonNull
        @Schema(
                description = "The work as every surface names it: its provider, the label the provider writes "
                        + "(\"#1423\", \"!1423\"), its title, where it sits and where to open it; the link is "
                        + "absent for deleted work")
        ReviewedWorkRefDTO reviewedWork,

        @NonNull @Schema(description = "Everything recorded about this artifact, oldest first")
        List<TracedSignalDTO> signals,

        @NonNull
        @Schema(
                description = "Every practice this workspace runs against this kind of work, the ones with "
                        + "something to report first, then the rest; ties broken by practice name")
        List<PracticeTraceEntryDTO> practices) {}
