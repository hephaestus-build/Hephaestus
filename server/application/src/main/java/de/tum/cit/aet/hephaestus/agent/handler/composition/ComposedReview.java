package de.tum.cit.aet.hephaestus.agent.handler.composition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Complete public bodies with exact support; admission accepts or refuses each body without rewriting it. */
public record ComposedReview(@Nullable Summary summary, List<InlineNote> inline, List<Withheld> withheld) {

    /** Earlier fragment contracts cannot produce fresh public delivery. */
    public static final int CONTRACT_VERSION = 2;

    public static final int MAX_SUMMARY_LENGTH = 8_000;

    public static final int MAX_INLINE_LENGTH = 2_000;

    /** Oversize reviews are refused, never shortened by selecting an arbitrary prefix. */
    public static final int MAX_INLINE_NOTES = 30;

    public ComposedReview {
        inline = List.copyOf(inline);
        withheld = List.copyOf(withheld);
    }

    public Set<String> decidedObservationIds() {
        Set<String> ids = new HashSet<>();
        if (summary != null) ids.addAll(summary.basedOn());
        inline.forEach(note -> ids.addAll(note.basedOn()));
        withheld.forEach(decision -> ids.addAll(decision.basedOn()));
        return Set.copyOf(ids);
    }

    public static ComposedReview empty() {
        return new ComposedReview(null, List.of(), List.of());
    }

    public record Summary(String body, List<String> basedOn) {
        public Summary {
            body = text(body, MAX_SUMMARY_LENGTH);
            basedOn = support(basedOn);
        }
    }

    /** The anchor is resolved from a supporting observation's citation, never model-authored coordinates. */
    public record InlineNote(String body, List<String> basedOn, ResolvedAnchor anchor) {
        public InlineNote {
            body = text(body, MAX_INLINE_LENGTH);
            Objects.requireNonNull(anchor, "anchor");
            basedOn = support(basedOn);
            if (!basedOn.contains(anchor.observationId())) {
                throw new IllegalArgumentException("A line note's anchor must be one of the observations it rests on");
            }
        }
    }

    /** Observations the composer decided not to raise on the work, with its reason. */
    public record Withheld(List<String> basedOn, ComposedFeedbackUnit.WithholdReason reason) {
        public Withheld {
            Objects.requireNonNull(reason, "reason");
            basedOn = support(basedOn);
        }
    }

    /** A text as written: never blank and never over its bound, with nothing trimmed from it. */
    private static String text(String body, int maxLength) {
        Objects.requireNonNull(body, "body");
        if (body.isBlank() || body.length() > maxLength) {
            throw new IllegalArgumentException("A composed text is blank or over its " + maxLength + " characters");
        }
        return body;
    }

    /** What a part rests on: at least one observation id, each named once, none blank. */
    private static List<String> support(List<String> basedOn) {
        List<String> ids = List.copyOf(basedOn);
        if (ids.isEmpty()
                || ids.stream().anyMatch(String::isBlank)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("A composed part rests on a non-empty list of distinct observation ids");
        }
        return ids;
    }

    /** A line resolved from one observation citation. */
    public record ResolvedAnchor(
            String observationId,
            int citationIndex,
            String path,
            @Nullable String side,
            int startLine,
            @Nullable Integer endLine) {
        public ResolvedAnchor {
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(path, "path");
        }
    }
}
