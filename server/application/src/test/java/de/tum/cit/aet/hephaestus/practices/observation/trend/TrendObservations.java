package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import java.time.Instant;
import java.util.UUID;

/**
 * The observations the trend and standing tests read: one piece of work a practice reviewed, and what it made
 * of it. A pull request unless a test says otherwise, and a run of its own unless the test names one, so a
 * test spells out only the facts it asserts on.
 */
public final class TrendObservations {

    private TrendObservations() {}

    /** The practice was demonstrated on this piece of work. */
    public static Observation clean(long artifactId, String observedAt) {
        return judged(artifactId, observedAt, Outcome.MET);
    }

    /** {@link #clean} on a kind other than a pull request. */
    public static Observation clean(long artifactId, String observedAt, ArtifactKind kind) {
        return observation(artifactId, UUID.randomUUID(), observedAt, kind, Outcome.MET);
    }

    /** {@link #clean} on a named run. */
    public static Observation clean(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, ArtifactKinds.PULL_REQUEST, Outcome.MET);
    }

    /** The practice was there to judge and was judged: {@code BAD} is a problem in what was done. */
    public static Observation judged(long artifactId, String observedAt, Outcome outcome) {
        return judged(artifactId, UUID.randomUUID(), observedAt, outcome);
    }

    /** {@link #judged} on a named run. */
    public static Observation judged(long artifactId, UUID jobId, String observedAt, Outcome outcome) {
        return observation(artifactId, jobId, observedAt, ArtifactKinds.PULL_REQUEST, outcome);
    }

    /** The practice was missing from this piece of work. */
    public static Observation problem(long artifactId, String observedAt) {
        return problem(artifactId, UUID.randomUUID(), observedAt);
    }

    /** {@link #problem} on a named run. */
    public static Observation problem(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, ArtifactKinds.PULL_REQUEST, Outcome.NOT_MET);
    }

    /** The practice looked at this piece of work and found nothing to judge. */
    public static Observation noVerdict(long artifactId, String observedAt) {
        return noVerdict(artifactId, UUID.randomUUID(), observedAt);
    }

    /** {@link #noVerdict} on a named run. */
    public static Observation noVerdict(long artifactId, UUID jobId, String observedAt) {
        return observation(artifactId, jobId, observedAt, ArtifactKinds.PULL_REQUEST, Outcome.NOT_APPLICABLE);
    }

    private static Observation observation(
            long artifactId, UUID jobId, String observedAt, ArtifactKind kind, Outcome outcome) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(jobId)
                .artifactKind(kind)
                .artifactId(artifactId)
                .outcome(outcome)
                .observedAt(Instant.parse(observedAt))
                .build();
    }
}
