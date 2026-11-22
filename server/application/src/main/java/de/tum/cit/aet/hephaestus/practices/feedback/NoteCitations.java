package de.tum.cit.aet.hephaestus.practices.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * What a note of a piece on the work cites: the problems that its composition support names, each by the practice of
 * its result and its identity. The public history and the readback read notes this way.
 */
final class NoteCitations {
    private NoteCitations() {}

    /** One problem that a note cites: its practice and the identity that a comparison matches it by. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Cite(String practiceSlug, String identity) {}

    /** A line note or a file note. A summary cites nothing here, because it may point to its notes. */
    static boolean note(FeedbackCopy copy) {
        return copy.getKind() == FeedbackCopyKind.LINE_NOTE || copy.getKind() == FeedbackCopyKind.FILE_NOTE;
    }

    /** The composition trace of the piece, or null when its review recorded none that reads. */
    static @Nullable FeedbackCompositionTrace trace(ObjectMapper mapper, Review review, UUID feedbackId) {
        var node = review.getChecks().path("feedbackComposition").path(feedbackId.toString());
        if (!node.isObject()) return null;
        try {
            return mapper.treeToValue(node, FeedbackCompositionTrace.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** What the copy rests on, as its composition support names it. */
    static List<FeedbackCompositionTrace.Support> support(FeedbackCompositionTrace trace, FeedbackCopy copy) {
        return trace.copies().stream()
                // Positions are unique within a piece; a note placed as a file note keeps its line note support.
                .filter(support -> support.position() == copy.getPosition())
                .flatMap(support -> support.basedOn().stream())
                .toList();
    }

    /**
     * The problems that one note cites, by practice and identity, each with the ids of the problems that carry it. A
     * problem without an identity, or of a result whose practice {@code practices} does not name, is left out.
     */
    static Map<Cite, List<UUID>> cites(
            FeedbackCompositionTrace trace,
            FeedbackCopy copy,
            Map<UUID, String> practices,
            Map<UUID, String> identities) {
        var cites = new LinkedHashMap<Cite, List<UUID>>();
        for (var basis : support(trace, copy)) {
            var practice = practices.get(basis.resultId());
            var problem = basis.problemId();
            var identity = problem == null ? null : identities.get(problem);
            if (practice != null && problem != null && identity != null)
                cites.computeIfAbsent(new Cite(practice, identity), ignored -> new ArrayList<>())
                        .add(problem);
        }
        return cites;
    }
}
