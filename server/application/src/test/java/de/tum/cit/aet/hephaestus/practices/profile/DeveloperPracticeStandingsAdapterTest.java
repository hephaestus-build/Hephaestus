package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendProperties;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings.Standing;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class DeveloperPracticeStandingsAdapterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long DEVELOPER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FeedbackObservationRepository feedbackObservationRepository;

    @Mock
    private CurrentDeveloperLookup currentDeveloperLookup;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private ObservationVisibilityPolicy visibilityPolicy;

    @Mock
    private WorkspaceReviewDefaultsProvider workspaceReviewDefaultsProvider;

    @Mock
    private BundledPracticeCatalogLoader bundledCatalog;

    @Mock
    private Clock clock;

    private DeveloperPracticeStandingsAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW);
        lenient().when(visibilityPolicy.permitsAll(anyLong(), any(), any())).thenAnswer(invocation -> {
            Collection<Observation> observations = invocation.getArgument(1);
            return observations.stream().map(Observation::getId).collect(Collectors.toSet());
        });
        lenient()
                .when(workspaceReviewDefaultsProvider.forWorkspace(WORKSPACE_ID))
                .thenReturn(WorkspaceReviewDefaults.UNSET);
        lenient()
                .when(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(any(), any(), any()))
                .thenReturn(List.of());
        PracticeStandingService standings = new PracticeStandingService(
                observationRepository,
                feedbackObservationRepository,
                currentDeveloperLookup,
                visibilityPolicy,
                practiceRepository,
                workspaceReviewDefaultsProvider,
                new PracticeTrendService(new TrendProperties(), clock),
                clock);
        adapter = new DeveloperPracticeStandingsAdapter(standings, bundledCatalog, clock);
    }

    @Test
    void shouldCountTheRequestedAndBackfilledWorkAStandingWasReadOffWhenNoLiveReviewJudgedIt() {
        Practice practice = new Practice();
        practice.setSlug("robust-error-handling");
        practice.setName("Handling failure robustly");
        practice.setCriteria("criteria");
        // A profile shows only the practices the workspace reviews today.
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practice));
        when(observationRepository.findByDeveloperAndWorkspaceBetween(
                        eq(DEVELOPER_ID), eq(WORKSPACE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        clean(practice, 41L, ObservationOrigin.BACKFILL),
                        clean(practice, 42L, ObservationOrigin.MANUAL),
                        clean(practice, 43L, ObservationOrigin.BACKFILL)));

        List<Standing> standings = adapter.standings(WORKSPACE_ID, DEVELOPER_ID);

        assertThat(standings).singleElement().satisfies(standing -> {
            assertThat(standing.verdict()).isTrue();
            assertThat(standing.decidedWork())
                    .as("the support of the standing, not the live runs it has none of")
                    .isEqualTo(3);
        });
    }

    private static Observation clean(Practice practice, long artifactId, ObservationOrigin origin) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .agentJobId(new UUID(0L, artifactId))
                .observedAt(NOW.minusSeconds(1_000_000L - artifactId * 1_000L))
                .summary("a strength")
                .outcome(Outcome.MET)
                .origin(origin)
                .build();
    }
}
