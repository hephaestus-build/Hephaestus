package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurringLapsesTest extends BaseUnitTest {

    private static Observation negative(String slug, long artifactId) {
        return observed(slug, artifactId, Presence.ABSENT);
    }

    private static Observation observed(String slug, long artifactId, Presence presence) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        return Observation.builder()
                .practice(practice)
                .workspaceId(1L)
                .aboutUserId(7L)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(presence)
                .assessment(Assessment.GOOD)
                .build();
    }

    @Test
    void shouldCallAPracticeRecurringOnlyPastThreeOtherPiecesOfWork() {
        ObservationRepository repository = mock(ObservationRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        // describe-what-and-why: negative on this work (42) and three earlier ones; ships-tests: on two.
        when(repository.findRecentForSubjectAndPractice(eq(1L), eq(7L), eq("describe-what-and-why"), any(), any()))
                .thenReturn(List.of(
                        negative("describe-what-and-why", 42),
                        negative("describe-what-and-why", 40),
                        negative("describe-what-and-why", 40),
                        negative("describe-what-and-why", 38),
                        negative("describe-what-and-why", 31)));
        when(repository.findRecentForSubjectAndPractice(
                        eq(1L), eq(7L), eq("ships-tests-with-the-change"), any(), any()))
                .thenReturn(List.of(
                        negative("ships-tests-with-the-change", 42),
                        negative("ships-tests-with-the-change", 40),
                        negative("ships-tests-with-the-change", 38)));

        var recurring = new RecurringLapses(repository, clock)
                .recurringSlugs(
                        List.of(negative("describe-what-and-why", 42), negative("ships-tests-with-the-change", 42)));

        assertThat(recurring).containsExactly("describe-what-and-why");
    }

    @Test
    void shouldNotCountALapseTheNextReviewFoundFixed() {
        ObservationRepository repository = mock(ObservationRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        Observation fixed = observed("describe-what-and-why", 40, Presence.PRESENT);
        // Newest first: work 40 was negative as a draft and fixed by the review that followed.
        when(repository.findRecentForSubjectAndPractice(eq(1L), eq(7L), eq("describe-what-and-why"), any(), any()))
                .thenReturn(List.of(
                        negative("describe-what-and-why", 42),
                        fixed,
                        negative("describe-what-and-why", 40),
                        negative("describe-what-and-why", 38),
                        negative("describe-what-and-why", 31)));

        var recurring =
                new RecurringLapses(repository, clock).recurringSlugs(List.of(negative("describe-what-and-why", 42)));

        assertThat(recurring).isEmpty();
    }
}
