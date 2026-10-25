package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Exact support and quiet decisions retained with the immutable preparation receipt. A public piece also retains the
 * publication context of the history read it was composed from; the server sets it, never the composer.
 */
public record FeedbackCompositionTrace(
        List<CopySupport> copies,
        List<QuietDecision> withheld,
        @Nullable UUID replacesId,
        @Nullable UUID primaryResultId,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String publicationContext) {
    public FeedbackCompositionTrace {
        copies = List.copyOf(copies);
        withheld = List.copyOf(withheld);
    }

    public FeedbackCompositionTrace(
            List<CopySupport> copies,
            List<QuietDecision> withheld,
            @Nullable UUID replacesId,
            @Nullable UUID primaryResultId) {
        this(copies, withheld, replacesId, primaryResultId, null);
    }

    public FeedbackCompositionTrace withPublicationContext(@Nullable String context) {
        return new FeedbackCompositionTrace(copies, withheld, replacesId, primaryResultId, context);
    }

    public record Support(UUID resultId, @Nullable UUID problemId) {}

    public record CopySupport(FeedbackCopyKind kind, int position, List<Support> basedOn) {
        public CopySupport {
            basedOn = List.copyOf(basedOn);
            if (position < 0 || basedOn.isEmpty())
                throw new IllegalArgumentException("A copy needs its exact support and position.");
        }
    }

    /** Why results stay unsaid: the composer's reason with the prior advice it rests on, or NOT_COMPOSED. */
    public record QuietDecision(List<Support> basedOn, String reason, List<String> witnessIds) {
        public static final String NOT_COMPOSED = "NOT_COMPOSED";

        public QuietDecision {
            basedOn = List.copyOf(basedOn);
            witnessIds = List.copyOf(witnessIds);
            if (basedOn.isEmpty()
                    || !List.of("BELOW_BAR", "ALREADY_SAID", "NO_MATERIAL_CHANGE", NOT_COMPOSED)
                            .contains(reason)
                    || (List.of("ALREADY_SAID", "NO_MATERIAL_CHANGE").contains(reason) && witnessIds.isEmpty()))
                throw new IllegalArgumentException("A quiet decision needs its reason and prior-advice witness.");
        }
    }
}
