package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Schema(
        oneOf = {PracticePageSupportDTO.Complete.class, PracticePageSupportDTO.Closed.class},
        discriminatorProperty = "state",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "COMPLETE", schema = PracticePageSupportDTO.Complete.class),
            @DiscriminatorMapping(value = "UNAVAILABLE", schema = PracticePageSupportDTO.Closed.class),
            @DiscriminatorMapping(value = "REFUSED", schema = PracticePageSupportDTO.Closed.class)
        })
public sealed interface PracticePageSupportDTO {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "CompletePracticePageSupportDTO")
    record Complete(
            @Schema(allowableValues = "COMPLETE") PracticePageSupport.State state,
            Instant readAt,
            List<PracticeSupportDTO> practices)
            implements PracticePageSupportDTO {
        public Complete {
            if (state != PracticePageSupport.State.COMPLETE)
                throw new IllegalArgumentException("Support is not complete");
            practices = List.copyOf(practices);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "ClosedPracticePageSupportDTO")
    record Closed(
            @Schema(allowableValues = {"UNAVAILABLE", "REFUSED"})
            PracticePageSupport.State state,

            Instant readAt,
            @Nullable String refusal)
            implements PracticePageSupportDTO {
        public Closed {
            if (state == PracticePageSupport.State.COMPLETE)
                throw new IllegalArgumentException("Complete support requires practices");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PracticeSupportDTO(
            String practiceSlug,
            PracticePageDecision decision,
            @Nullable FeedbackStopReason stopReason,
            List<SupportOccurrenceDTO> occurrences) {
        public PracticeSupportDTO {
            occurrences = List.copyOf(occurrences);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SupportOccurrenceDTO(
            UUID resultId,
            String itemKind,
            String itemId,
            @Nullable ItemRefDTO item,
            ResultOutcome outcome,
            ReviewOrigin origin,
            String summary,
            @Nullable String explanation,
            @Nullable JsonNode evidence,
            @Nullable String practiceVersionId,
            Instant recordedAt) {}
}
