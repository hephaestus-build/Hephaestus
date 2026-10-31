package de.tum.cit.aet.hephaestus.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One authorized history read. {@code publicationContext} names this exact response: preparation of a public piece
 * composed from it must send it back unchanged.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicFeedbackSnapshot(
        Instant readAt, @Nullable String publicationContext, History history) {
    public record History(ItemRefDTO item, List<Entry> feedback) {
        public History {
            feedback = List.copyOf(feedback);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(
            UUID id,
            FeedbackDestination destination,
            FeedbackState state,
            Instant deliveredAt,
            @Nullable String itemVersion,
            @Nullable String body,
            boolean bodyUnavailable,
            Currentness currentness,
            boolean withdrawn,
            List<Basis> basedOn,
            List<Copy> copies) {
        public Entry {
            basedOn = List.copyOf(basedOn);
            copies = List.copyOf(copies);
            if (destination != FeedbackDestination.ON_WORK)
                throw new IllegalArgumentException("Public history requires ON_WORK");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Basis(
            UUID resultId, String practiceSlug, @Nullable String practiceVersionId, ResultOutcome outcome) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Copy(
            UUID id,
            FeedbackCopyKind kind,
            @Nullable String body,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable CitationSide side,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            Instant postedAt,
            FeedbackCopyState state) {
        public Copy {
            if (state != FeedbackCopyState.POSTED && state != FeedbackCopyState.RETRACTED)
                throw new IllegalArgumentException("Public history requires a confirmed or retracted copy");
        }
    }
}
