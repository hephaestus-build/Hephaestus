package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationDelta.Locus;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationDelta.LocusChange;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationDelta.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bookkeeping the composition stage is not asked to do.
 *
 * <p>Every case here is a different sentence the composer is or is not licensed to write, so the
 * assertions are about the status rather than about the shape of the record.
 */
@Tag("unit")
class ObservationDeltaTest {

    private static final Instant OLDER = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant NEWER = OLDER.plus(3, ChronoUnit.DAYS);

    private static final UUID FIRST_RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND_RUN = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void aLocusMeasuredOnlyByTheNewestRunIsNew() {
        ObservationDelta delta =
                ObservationDelta.classify(List.of(locus("k1", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MAJOR)));

        assertThat(statusOf(delta, "k1")).contains(Status.NEW);
        assertThat(changeOf(delta, "k1"))
                .get()
                .extracting(LocusChange::runsSeen)
                .isEqualTo(1);
    }

    @Test
    void aProblemStillPresentButAtADifferentSeverityIsRecurring() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                locus("k1", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MINOR),
                locus("k1", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.CRITICAL)));

        assertThat(statusOf(delta, "k1")).contains(Status.RECURRING);
        assertThat(changeOf(delta, "k1"))
                .get()
                .extracting(LocusChange::latestSeverity)
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    void aProblemStillPresentAndUnmovedIsUnchanged() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                locus("k1", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MAJOR),
                locus("k1", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MAJOR)));

        assertThat(statusOf(delta, "k1")).contains(Status.UNCHANGED);
    }

    @Test
    void aProblemAbsentFromTheNewestRunOfItsArtifactIsResolved() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                locus("gone", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MAJOR),
                locus("still-here", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MINOR),
                locus("still-here", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MINOR)));

        assertThat(statusOf(delta, "gone")).contains(Status.RESOLVED);
        assertThat(changeOf(delta, "gone"))
                .get()
                .extracting(LocusChange::latestOutcome)
                .isEqualTo(Outcome.NEGATIVE);
    }

    /** Crediting somebody with fixing what was already right is the one wrong answer this must not give. */
    @Test
    void aStrengthThatWasNotReObservedIsNotResolved() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                locus("praise", FIRST_RUN, OLDER, Outcome.POSITIVE, null),
                locus("problem", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MINOR),
                locus("problem", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MINOR)));

        assertThat(changeOf(delta, "praise")).isEmpty();
    }

    /**
     * The recurrence key folds in the artifact, so two merge requests are two loci. A run on one artifact
     * must never make a locus on another look resolved.
     */
    @Test
    void aNewerRunOnOneArtifactDoesNotResolveAnotherArtifactsLocus() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                new Locus(
                        "mr-18",
                        "ships-tests",
                        ArtifactKinds.PULL_REQUEST,
                        18L,
                        FIRST_RUN,
                        OLDER,
                        Outcome.NEGATIVE,
                        Severity.MAJOR),
                new Locus(
                        "mr-22",
                        "ships-tests",
                        ArtifactKinds.PULL_REQUEST,
                        22L,
                        SECOND_RUN,
                        NEWER,
                        Outcome.NEGATIVE,
                        Severity.MAJOR)));

        assertThat(statusOf(delta, "mr-18")).contains(Status.NEW);
        assertThat(statusOf(delta, "mr-22")).contains(Status.NEW);
    }

    @Test
    void locusWithoutARecurrenceKeyIsSkippedRatherThanGroupedWithTheOthers() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                new Locus(
                        null,
                        "ships-tests",
                        ArtifactKinds.PULL_REQUEST,
                        22L,
                        SECOND_RUN,
                        NEWER,
                        Outcome.NEGATIVE,
                        Severity.MAJOR),
                locus("k1", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MAJOR)));

        assertThat(delta.loci()).extracting(LocusChange::recurrenceKey).containsExactly("k1");
    }

    /** A truncating reader keeps what moved, so the order is part of the contract. */
    @Test
    void whatMovedIsOrderedAheadOfWhatDidNot() {
        ObservationDelta delta = ObservationDelta.classify(List.of(
                locus("unmoved", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MAJOR),
                locus("unmoved", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MAJOR),
                locus("fresh", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.MAJOR),
                locus("worse", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MINOR),
                locus("worse", SECOND_RUN, NEWER, Outcome.NEGATIVE, Severity.CRITICAL),
                locus("fixed", FIRST_RUN, OLDER, Outcome.NEGATIVE, Severity.MAJOR)));

        assertThat(delta.loci())
                .extracting(LocusChange::recurrenceKey)
                .containsExactly("fixed", "worse", "fresh", "unmoved");
    }

    @Test
    void anEmptyWindowClassifiesNothing() {
        assertThat(ObservationDelta.classify(List.of()).loci()).isEmpty();
    }

    private static Locus locus(String key, UUID runId, Instant at, Outcome outcome, @Nullable Severity severity) {
        return new Locus(key, "ships-tests", ArtifactKinds.PULL_REQUEST, 22L, runId, at, outcome, severity);
    }

    private static Optional<Status> statusOf(ObservationDelta delta, String key) {
        return changeOf(delta, key).map(LocusChange::status);
    }

    private static Optional<LocusChange> changeOf(ObservationDelta delta, String key) {
        return delta.loci().stream()
                .filter(change -> change.recurrenceKey().equals(key))
                .findFirst();
    }
}
