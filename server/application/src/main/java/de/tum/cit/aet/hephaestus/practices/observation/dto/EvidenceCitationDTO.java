package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Schema(description = "A verified quote and its exact source location")
public record EvidenceCitationDTO(
        @NonNull String sourceKind,
        @NonNull String artifactPath,
        @NonNull String path,
        @Nullable EvidenceCitationSide side,
        @Nullable String revision,
        @NonNull Integer startLine,
        @NonNull Integer endLine,
        @Nullable String quote,
        @NonNull Boolean quoteRedacted) {
    public EvidenceCitationDTO {
        if (sourceKind.isBlank()
                || artifactPath.isBlank()
                || path.isBlank()
                || (revision != null
                        && (!sourceKind.equals("scm.repository.tree")
                                || !revision.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")))
                || startLine < 1
                || endLine < startLine
                || (sourceKind.equals("scm.pull-request.diff") != (side != null))
                || (quoteRedacted ? quote != null : quote == null || quote.isBlank())) {
            throw new IllegalArgumentException("Invalid evidence citation");
        }
    }

    static EvidenceCitationDTO from(JsonNode citation) {
        String quote = citation.path("quote").asString(null);
        boolean quoteRedacted = citation.path("quoteRedacted").asBoolean(false);
        return new EvidenceCitationDTO(
                citation.path("sourceKind").asString(),
                citation.path("artifactPath").asString(),
                citation.path("path").asString(),
                parseSide(citation.path("side").asString(null)),
                citation.path("revision").asString(null),
                citation.path("startLine").asInt(),
                citation.path("endLine").asInt(citation.path("startLine").asInt()),
                quote,
                quoteRedacted);
    }

    private static @Nullable EvidenceCitationSide parseSide(@Nullable String side) {
        return side == null ? null : EvidenceCitationSide.valueOf(side);
    }
}
