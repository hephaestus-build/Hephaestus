package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One recorded occurrence on the traced artifact, exactly as the ledger holds it. */
@Schema(description = "One thing that happened to this artifact, and what the system did about it")
public record TracedSignalDTO(
        @NonNull @Schema(description = "This occurrence's own identity; what a practice's occasionedById points at")
        UUID id,

        @NonNull @Schema(description = "Signal name, e.g. scm.pull_request.ready")
        SignalName signal,

        @NonNull @Schema(description = "Human label for the signal, from the artifact kind's descriptor")
        String displayName,

        @NonNull
        @Schema(
                description = "Which version of the artifact this occurrence is about; the reason editing a "
                        + "description can be re-measured while the commits stay put")
        String revision,

        @NonNull @Schema(description = "When it happened upstream; for a sync discovery, only as precise as the sync")
        Instant occurredAt,

        @NonNull @Schema(description = "How we came to know: by event, by sync, by hand, or by backfill")
        DiscoveredVia discoveredVia,

        @NonNull SignalState state,

        @Schema(description = "Why it ended in that state; null once it triggered a review") @Nullable
        SignalStateReason stateReason,

        @Schema(description = "The review this occurrence started, when it started one") @Nullable
        UUID reviewId,

        @Schema(
                description = "Where that review's run stands now, which a practice's own outcome does not "
                        + "say once an earlier review of this work has results; null when no linked run is available")
        @Nullable
        ReviewRunState reviewState) {
    public static TracedSignalDTO from(
            ArtifactSignal signal, @Nullable String displayName, @Nullable ReviewOutcome review) {
        SignalName name = SignalName.of(signal.getSignalName());
        return new TracedSignalDTO(
                signal.getId(),
                name,
                displayName == null || displayName.isBlank() ? name.value() : displayName,
                signal.getRevision(),
                signal.getOccurredAt(),
                signal.getDiscoveredVia(),
                signal.getState(),
                signal.getStateReason(),
                signal.getJobId(),
                review == null ? null : review.state());
    }
}
