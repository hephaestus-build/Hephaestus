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
        @Nullable List<String> sourceKinds) {}
