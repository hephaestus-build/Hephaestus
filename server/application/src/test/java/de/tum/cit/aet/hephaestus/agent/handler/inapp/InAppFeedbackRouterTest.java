package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every refusal the in-app lane can give, and the one admission. Each case differs from the admitted
 * one in exactly the fact under test, so a rule that stopped firing shows up here as a wrong reason
 * rather than as a silently admitted message.
 */
class InAppFeedbackRouterTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void admitsACorroboratedLivePatternNobodyHasBeenShownLately() {
        assertThat(route(problems(2, ObservationOrigin.LIVE), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.ADMIT);
    }

    @Test
    void refusesAMessageMissingItsNextStep() {
        ComposedInAppMessage incomplete = new ComposedInAppMessage("ships-tests", "Title", "Body", "  ", null);

        assertThat(InAppFeedbackRouter.route(
                        incomplete,
                        problems(2, ObservationOrigin.LIVE),
                        PracticeAutonomy.AUTOMATIC,
                        ActorRole.AUTHOR,
                        null,
                        NOW))
                .isEqualTo(InAppRoutingDecision.INCOMPLETE);
    }

    /**
     * The surveillance guard. A practice about how somebody REVIEWS is filed against the artifact's
     * author today, so showing it on the author's own page would hand them a judgement of work they did
     * not do — and it must be refused before any question about the evidence, since the evidence is the
     * part that is wrong.
     */
    @Test
    void refusesAPracticeThatJudgesSomebodyOtherThanTheAuthor() {
        assertThat(route(problems(5, ObservationOrigin.LIVE), PracticeAutonomy.AUTOMATIC, ActorRole.REVIEWER, null))
                .isEqualTo(InAppRoutingDecision.REVIEWER_ATTRIBUTED);
    }

    @Test
    void refusesAPracticeWithNoProblemsBehindIt() {
        Observation strength = observation(1L, ObservationOrigin.LIVE, Assessment.GOOD);

        assertThat(route(List.of(strength), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.NO_EVIDENCE);
    }

    @Test
    void refusesAPracticeWhoseTierDoesNotAdmitTheLane() {
        assertThat(route(problems(2, ObservationOrigin.LIVE), PracticeAutonomy.HUMAN_APPROVAL, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.PRACTICE_REQUIRES_APPROVAL);
    }

    /** The day-one bound: a sweep over a year of finished work does not become a wall of feedback. */
    @Test
    void refusesAPatternWhoseEveryMeasurementCameFromABackfill() {
        assertThat(route(problems(4, ObservationOrigin.BACKFILL), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.BACKFILL_HELD);
    }

    /** One live measurement is enough to make the cluster a live one; the refusal is for a wholly backfilled set. */
    @Test
    void admitsAPatternThatMixesBackfilledAndLiveMeasurements() {
        Observation backfilled = observation(1L, ObservationOrigin.BACKFILL, Assessment.BAD);
        Observation live = observation(2L, ObservationOrigin.LIVE, Assessment.BAD);

        assertThat(route(List.of(backfilled, live), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.ADMIT);
    }

    /** One occurrence is a task-level note; it was already delivered where it belongs. */
    @Test
    void refusesAProblemSeenOnOnlyOnePieceOfWork() {
        assertThat(route(problems(1, ObservationOrigin.LIVE), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.UNCORROBORATED);
    }

    /** Twice on the same pull request is one occurrence — the unit of proof here is separate work. */
    @Test
    void countsTwoProblemsOnOneArtifactAsOneOccurrence() {
        Observation first = observation(42L, ObservationOrigin.LIVE, Assessment.BAD);
        Observation second = observation(42L, ObservationOrigin.LIVE, Assessment.BAD);

        assertThat(route(List.of(first, second), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.UNCORROBORATED);
    }

    @Test
    void refusesAHabitTheDeveloperWasShownInsideTheCooldown() {
        Instant yesterday = NOW.minus(Duration.ofDays(1));

        assertThat(route(problems(3, ObservationOrigin.LIVE), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, yesterday))
                .isEqualTo(InAppRoutingDecision.RECENTLY_SURFACED);
    }

    @Test
    void admitsAHabitLastShownBeforeTheCooldownElapsed() {
        Instant longAgo = NOW.minus(Duration.ofDays(InAppFeedbackRouter.RESURFACE_COOLDOWN_DAYS + 1));

        assertThat(route(problems(3, ObservationOrigin.LIVE), PracticeAutonomy.AUTOMATIC, ActorRole.AUTHOR, longAgo))
                .isEqualTo(InAppRoutingDecision.ADMIT);
    }

    @Test
    void unresolvedAutonomyFailsClosed() {
        assertThat(route(problems(2, ObservationOrigin.LIVE), null, ActorRole.AUTHOR, null))
                .isEqualTo(InAppRoutingDecision.PRACTICE_REQUIRES_APPROVAL);
    }

    /**
     * The evidence a card lists is the problems, not the window they were found in. Binding the window
     * would put work where the practice went WELL under the heading "the pieces of work this habit was
     * observed on", which reads as a false accusation to the one person who knows it is false.
     */
    @Test
    void narrowsAWindowOfMeasurementsToJustTheProblems() {
        Observation problem = observation(1L, ObservationOrigin.LIVE, Assessment.BAD);
        Observation strength = observation(2L, ObservationOrigin.LIVE, Assessment.GOOD);
        Observation abstention = Observation.builder()
                .id(UUID.randomUUID())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(3L)
                .assessmentStatus(AssessmentStatus.NOT_APPLICABLE)
                .presence(null)
                .origin(ObservationOrigin.LIVE)
                .observedAt(NOW)
                .build();

        assertThat(InAppFeedbackRouter.problemsIn(List.of(problem, strength, abstention)))
                .containsExactly(problem);
    }

    private static InAppRoutingDecision route(
            List<Observation> evidence,
            @Nullable PracticeAutonomy autonomy,
            ActorRole subjectRole,
            @Nullable Instant lastSurfaced) {
        return InAppFeedbackRouter.route(message(), evidence, autonomy, subjectRole, lastSurfaced, NOW);
    }

    private static ComposedInAppMessage message() {
        return new ComposedInAppMessage(
                "ships-tests-with-the-change",
                "Tests are arriving one commit late",
                "On your last few changes the test landed a push after the behaviour did.",
                "Write the assertion that distinguishes the new branch before you write the branch.",
                null);
    }

    /** {@code count} problems, each on a different piece of work. */
    private static List<Observation> problems(int count, ObservationOrigin origin) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> observation(i, origin, Assessment.BAD))
                .toList();
    }

    private static Observation observation(long artifactId, ObservationOrigin origin, Assessment assessment) {
        ArtifactKind kind = ArtifactKinds.PULL_REQUEST;
        return Observation.builder()
                .id(UUID.randomUUID())
                .artifactKind(kind)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(assessment)
                .origin(origin)
                .observedAt(NOW)
                .build();
    }
}
