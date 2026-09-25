package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendProperties;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticeStandingVisibilityTest extends BaseUnitTest {

    private static final Long WORKSPACE_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final Long USER_ID = 7L;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FeedbackObservationRepository feedbackObservationRepository;

    @Mock
    private CurrentDeveloperLookup currentDeveloperLookup;

    @Mock
    private ObservationVisibilityPolicy visibilityPolicy;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private WorkspaceReviewDefaultsProvider workspaceReviewDefaultsProvider;

    @Mock
    private Clock clock;

    private PracticeStandingService practiceStandingService;

    @BeforeEach
    void setUp() {
        when(currentDeveloperLookup.currentDeveloperId()).thenReturn(Optional.of(USER_ID));
        when(clock.instant()).thenReturn(NOW);
        lenient()
                .when(workspaceReviewDefaultsProvider.forWorkspace(WORKSPACE_ID))
                .thenReturn(WorkspaceReviewDefaults.UNSET);
        lenient()
                .when(visibilityPolicy.permitsAll(
                        eq(WORKSPACE_ID), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(invocation -> {
                    Collection<Observation> batch = invocation.getArgument(1);
                    return batch.stream().map(Observation::getId).collect(Collectors.toSet());
                });
        practiceStandingService = new PracticeStandingService(
                observationRepository,
                feedbackObservationRepository,
                currentDeveloperLookup,
                visibilityPolicy,
                practiceRepository,
                workspaceReviewDefaultsProvider,
                new PracticeTrendService(new TrendProperties(), clock),
                clock);
    }

    private Observation bad(Practice practice, @org.jspecify.annotations.Nullable Severity severity) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .observedAt(NOW.minusSeconds(3600))
                .agentJobId(new UUID(0L, 42L))
                .summary("a problem")
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.ABSENT)
                .assessment(Assessment.GOOD)
                .severity(severity)
                .build();
    }

    private static Practice practice(String slug) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        practice.setName("Handling failure robustly");
        practice.setCriteria("ordinary criteria"); // not a defect-detector
        return practice;
    }

    @Test
    void withholdsObservationRejectedByFeedbackVisibilityPolicy() {
        Practice practice = practice("robust-error-handling");
        Observation observation = bad(practice, Severity.MAJOR);
        when(observationRepository.findByDeveloperAndWorkspaceBetween(
                        eq(USER_ID), eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(observation));
        when(visibilityPolicy.permitsAll(
                        WORKSPACE_ID, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());

        assertThat(practiceStandingService.getStandings(WORKSPACE_ID)).isEmpty();
        verifyNoInteractions(feedbackObservationRepository);
    }

    @Test
    @DisplayName("a BAD observation with null severity does not NPE the sort and ranks after a graded one")
    void nullSeverityDoesNotBreakStandingSort() {
        Practice practice = new Practice();
        practice.setSlug("robust-error-handling");
        practice.setName("Handling failure robustly");
        practice.setCriteria("ordinary criteria"); // not a defect-detector

        when(observationRepository.findByDeveloperAndWorkspaceBetween(
                        eq(USER_ID), eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(bad(practice, null), bad(practice, Severity.CRITICAL)));
        when(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(any(), any(), any()))
                .thenReturn(List.of());

        List<PracticeStandingDTO> standings = practiceStandingService.getStandings(WORKSPACE_ID);

        assertThat(standings).hasSize(1);
        List<Severity> order =
                standings.get(0).toWorkOn().stream().map(i -> i.severity()).toList();
        // CRITICAL leads; the null-severity item sorts last (treated as least-severe).
        assertThat(order).containsExactly(Severity.CRITICAL, null);
    }

    private Observation strength(Practice practice, @Nullable Presence presence) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .observedAt(NOW.minusSeconds(3600))
                .agentJobId(new UUID(0L, 42L))
                .summary("nothing swallowed on the paths you added")
                .assessmentStatus(presence == null ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.ASSESSED)
                .presence(presence)
                .assessment(presence == null ? null : presence == Presence.PRESENT ? Assessment.GOOD : Assessment.BAD)
                .build();
    }

    private void feeds(Observation... observations) {
        when(observationRepository.findByDeveloperAndWorkspaceBetween(
                        eq(USER_ID), eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(observations));
        when(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("ABSENT/BAD is shown as a strength")
    void shouldShowAbsentBadAsStrength() {
        Practice practice = practice("handles-errors-instead-of-swallowing-them");
        feeds(strength(practice, Presence.ABSENT));

        List<PracticeStandingDTO> standings = practiceStandingService.getStandings(WORKSPACE_ID);

        assertThat(standings).hasSize(1);
        assertThat(standings.get(0).strengths()).hasSize(1);
        assertThat(standings.get(0).toWorkOn()).isEmpty();
    }

    @Test
    @DisplayName("PRESENT/GOOD supports the standing for an error-handling practice")
    void shouldShowPresentGoodAsStrengthForErrorHandling() {
        Practice practice = practice("handles-errors-instead-of-swallowing-them");
        feeds(strength(practice, Presence.PRESENT));

        List<PracticeStandingDTO> standings = practiceStandingService.getStandings(WORKSPACE_ID);

        assertThat(standings).hasSize(1);
        assertThat(standings.get(0).strengths()).hasSize(1);
        assertThat(standings.get(0).toWorkOn()).isEmpty();
    }

    @Test
    @DisplayName("an ordinary practice keeps both shapes of strength")
    void ordinaryPracticeKeepsBothShapesOfStrength() {
        Practice practice = practice("robust-error-handling");
        feeds(strength(practice, Presence.PRESENT), strength(practice, Presence.ABSENT));

        List<PracticeStandingDTO> standings = practiceStandingService.getStandings(WORKSPACE_ID);

        assertThat(standings).hasSize(1);
        assertThat(standings.get(0).strengths()).hasSize(2);
    }
}
