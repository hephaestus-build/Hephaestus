package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ReviewedWorkKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The occurrences a new IN_APP card may cite at {@code readAt}, per current NOT_MET practice, as the page
 * selects them. It is read after the observations are admitted and is not part of the admission: a later read can
 * differ, and the page decides again whether a card is written.
 *
 * @param refusal the recipient policy's reason, for {@link State#REFUSED}
 */
public record InAppSupportContext(
        State state, String readAt, @Nullable FeedbackSuppressionReason refusal, List<PracticeSupport> practices) {

    public enum State {
        /** Every current NOT_MET practice was read; a practice without occurrences has none to cite. */
        COMPLETE,
        /** The read failed or could not be attributed to one recipient; it says nothing about the support. */
        UNAVAILABLE,
        /** The recipient policy does not allow an IN_APP card for this run. */
        REFUSED,
    }

    public record PracticeSupport(String practiceSlug, List<Occurrence> occurrences) {}

    /** One selected observation, with the native identity of the work it is about and what it recorded. */
    public record Occurrence(
            UUID observationId,
            String artifactKind,
            long artifactId,
            String outcome,
            String origin,
            String summary,
            @Nullable String evidenceRationale,
            @Nullable JsonNode evidence,
            @Nullable Long practiceRevisionId,
            String observedAt) {

        static Occurrence of(Observation observation) {
            ReviewedWorkKey work = ReviewedWorkKey.of(observation);
            PracticeRevision revision = observation.getPracticeRevision();
            return new Occurrence(
                    observation.getId(),
                    work.kind().value(),
                    work.id(),
                    observation.getOutcome().name(),
                    observation.getOrigin().name(),
                    observation.getSummary(),
                    observation.getEvidenceRationale(),
                    observation.getEvidence(),
                    revision == null ? null : revision.getId(),
                    observation.getObservedAt().toString());
        }
    }

    static InAppSupportContext complete(Instant readAt, List<PracticeSupport> practices) {
        return new InAppSupportContext(State.COMPLETE, readAt.toString(), null, List.copyOf(practices));
    }

    public static InAppSupportContext unavailable(Instant readAt) {
        return new InAppSupportContext(State.UNAVAILABLE, readAt.toString(), null, List.of());
    }

    static InAppSupportContext refused(Instant readAt, @Nullable FeedbackSuppressionReason reason) {
        return new InAppSupportContext(State.REFUSED, readAt.toString(), reason, List.of());
    }
}
