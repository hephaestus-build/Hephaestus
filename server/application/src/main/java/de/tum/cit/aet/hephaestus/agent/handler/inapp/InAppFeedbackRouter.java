package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomyPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class InAppFeedbackRouter {

    /**
     * How long the same practice stays quiet on this surface after it was last shown. A habit does not
     * change week to week; re-posting it every time a pull request lands turns a private surface into
     * nagging and teaches the developer to stop opening it.
     */
    public static final int RESURFACE_COOLDOWN_DAYS = 14;

    /**
     * How far back a pattern may reach for its evidence. Matches the reflective surface and the review
     * history staged into the sandbox, so what the composer read and what the router counts are the same
     * window.
     */
    public static final int PATTERN_WINDOW_DAYS = 90;

    private InAppFeedbackRouter() {}

    public static InAppRoutingDecision route(
            ComposedInAppMessage message,
            List<Observation> evidence,
            @Nullable PracticeAutonomy autonomy,
            ActorRole subjectRole,
            @Nullable Instant lastSurfaced,
            Instant now) {
        if (!message.isComplete()) {
            return InAppRoutingDecision.INCOMPLETE;
        }
        // Checked before anything else about the evidence: a message about a practice whose results may
        // be filed against the wrong person must not be shown to that person, whatever else is true of it.
        if (subjectRole != ActorRole.AUTHOR) {
            return InAppRoutingDecision.REVIEWER_ATTRIBUTED;
        }
        List<Observation> problems = problemsIn(evidence);
        if (problems.isEmpty()) {
            return InAppRoutingDecision.NO_EVIDENCE;
        }
        // Origin is asked of the evidence, not of the run: what makes a pattern claim sound is how the
        // measurements behind it were taken, and a live run can compose over a backfilled record.
        ObservationOrigin origin = weakestOrigin(problems);
        if (!PracticeAutonomyPolicy.delivers(origin, autonomy, FeedbackChannel.IN_APP)) {
            return InAppRoutingDecision.PRACTICE_REQUIRES_APPROVAL;
        }
        if (problems.stream().allMatch(o -> o.getOrigin() == ObservationOrigin.BACKFILL)) {
            return InAppRoutingDecision.BACKFILL_HELD;
        }
        if (distinctArtifacts(problems) < FeedbackCompositionInputs.MIN_DISTINCT_ARTIFACTS) {
            return InAppRoutingDecision.UNCORROBORATED;
        }
        if (lastSurfaced != null && lastSurfaced.isAfter(now.minus(Duration.ofDays(RESURFACE_COOLDOWN_DAYS)))) {
            return InAppRoutingDecision.RECENTLY_SURFACED;
        }
        return InAppRoutingDecision.ADMIT;
    }

    /**
     * The provenance the whole cluster is only as strong as. A cluster containing one backfilled
     * measurement is a live cluster; a cluster containing only backfilled ones is a backfilled one, and
     * {@link InAppRoutingDecision#BACKFILL_HELD} answers it below. LIVE is returned for an empty
     * cluster, which the caller has already refused.
     */
    private static ObservationOrigin weakestOrigin(List<Observation> problems) {
        return problems.stream().anyMatch(o -> o.getOrigin() != ObservationOrigin.BACKFILL)
                ? ObservationOrigin.LIVE
                : ObservationOrigin.BACKFILL;
    }

    /**
     * The subset of a practice's measurements that a message about a recurring problem may stand on:
     * the ones that actually recorded a problem.
     *
     * <p>Public because it is the single definition of "the evidence" for this lane, and both the
     * decision here and the rows bound to the written unit must use it. Binding the unfiltered window
     * instead would list, under "the pieces of work this habit was observed on", work where the practice
     * was done well — which reads as a false accusation to the one person who knows it is false.
     */
    public static List<Observation> problemsIn(List<Observation> evidence) {
        return evidence.stream()
                .filter(o -> o.getAssessmentStatus() == AssessmentStatus.ASSESSED)
                .filter(o -> o.getAssessment() == Assessment.BAD)
                .toList();
    }

    /** How many separate pieces of work carry the problem — the unit of proof at the process level. */
    public static int distinctArtifacts(List<Observation> problems) {
        Set<String> artifacts = new HashSet<>();
        for (Observation problem : problems) {
            if (problem.getArtifactKind() == null || problem.getArtifactId() == null) {
                continue;
            }
            artifacts.add(problem.getArtifactKind().value() + ":" + problem.getArtifactId());
        }
        return artifacts.size();
    }
}
