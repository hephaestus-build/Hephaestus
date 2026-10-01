package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
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

        @NonNull @Schema(description = "Signal name, for matching and linking; never printed")
        SignalName signal,

        @NonNull @Schema(description = "What to print for the signal, from the artifact kind's descriptor")
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

        @Schema(
                description = "That reason as one sentence for a reader. Render it verbatim: it is written next to "
                        + "the reason it explains, so every surface that explains a silence says the same thing")
        @Nullable
        String stateReasonDescription,

        @Schema(description = "The review this occurrence started, when it started one") @Nullable
        UUID reviewId) {
    /** @param displayName the signal's words, already resolved; this record never falls back to the name */
    public static TracedSignalDTO from(ArtifactSignal signal, String displayName) {
        SignalStateReason reason = signal.getStateReason();
        return new TracedSignalDTO(
                signal.getId(),
                SignalName.of(signal.getSignalName()),
                displayName,
                signal.getRevision(),
                signal.getOccurredAt(),
                signal.getDiscoveredVia(),
                signal.getState(),
                reason,
                reason == null ? null : reason.describe(),
                signal.getJobId());
    }
}
