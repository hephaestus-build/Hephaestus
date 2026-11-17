package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a read of results needs to place, authorize and summarize a result, without its evidence, explanation or
 * example: {@link Result#facts()} of a full record, or a history read's row.
 *
 * @param sourceKinds the {@linkplain Result#sourceKinds(JsonNode, JsonNode) source kinds that the result rests on};
 *     null withholds the result, because one of them is not named
 */
public record ResultFacts(
        UUID id,
        long workspaceId,
        UUID reviewId,
        long practiceId,
        @Nullable Long practiceVersionId,
        long itemId,
        @Nullable String itemVersion,
        @Nullable Long developerId,
        ResultOutcome outcome,
        @Nullable Severity severity,
        @Nullable String summary,
        boolean migratedHistory,
        Instant recordedAt,
        @Nullable Instant endedAt,
        @Nullable ResultEndReason endReason,
        boolean hasExplanation,
        boolean hasEvidence,
        boolean hasCoverage,
        @Nullable List<String> sourceKinds) {

    /** Whether the result was captured under the current contract and kept every part of its proof. */
    public boolean carriesCapturedProof() {
        return capturedProof(migratedHistory, summary, hasExplanation, hasEvidence, hasCoverage);
    }

    /** Whether the result can be admitted as proof: captured proof that names the practice and item versions it judged. */
    public boolean isAdmissibleProof() {
        return admissibleProof(carriesCapturedProof(), practiceVersionId, itemVersion);
    }

    /** The rule of {@link #carriesCapturedProof()}, which {@link Result} applies to its own fields. */
    static boolean capturedProof(
            boolean migratedHistory,
            @Nullable String summary,
            boolean hasExplanation,
            boolean hasEvidence,
            boolean hasCoverage) {
        return !migratedHistory && summary != null && hasExplanation && hasEvidence && hasCoverage;
    }

    /** The rule of {@link #isAdmissibleProof()}, which {@link Result} applies to its own fields. */
    static boolean admissibleProof(
            boolean capturedProof, @Nullable Long practiceVersionId, @Nullable String itemVersion) {
        return capturedProof && practiceVersionId != null && itemVersion != null;
    }
}
