package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Durable delivery facts for the on-work composition of one sealed review.
 *
 * @param repeats each note of this feedback, found and not resolved on the provider, that cites a problem that another
 *     delivered note on the same work also cites; advice only, empty when there is none
 */
public record FeedbackRepairStatus(
        @Nullable UUID feedbackId, State state, int edits, List<CopyReadback> copies, List<Repeat> repeats) {
    public enum State {
        WAITING,
        AWAITING_APPROVAL,
        READBACK,
        STOPPED
    }

    public FeedbackRepairStatus {
        copies = List.copyOf(copies);
        repeats = List.copyOf(repeats);
    }

    public FeedbackRepairStatus(@Nullable UUID feedbackId, State state, int edits, List<CopyReadback> copies) {
        this(feedbackId, state, edits, copies, List.of());
    }

    /** One note of this feedback and the other delivered note that cites the same problem. */
    public record Repeat(UUID copyId, List<UUID> problemIds, OtherNote otherNote) {
        public Repeat {
            problemIds = List.copyOf(problemIds);
        }
    }

    /**
     * Where the other note sits, its link, and its words; no words when the read may not copy them. It raised the
     * problem; whether a person resolved its thread is not known here.
     */
    public record OtherNote(
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable String link,
            @Nullable String body) {}
}
