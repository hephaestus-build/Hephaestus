package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Where a new card's evidence begins, and which card it replaces. The window is ninety days, but a card about
 * a habit the developer was already shown may only cite what came after the answer — or, while the previous
 * card is still open and about to be replaced, after that card was prepared.
 */
class InAppCompositionListenerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 11L;
    private static final long RECIPIENT_ID = 12L;
    private static final String PRACTICE = "ships-tests-with-the-change";

    private final AgentJobRepository agentJobRepository = mock(AgentJobRepository.class);
    private final ObservationRepository observationRepository = mock(ObservationRepository.class);
    private final FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
    private final ObservationVisibilityPolicy visibilityPolicy = mock(ObservationVisibilityPolicy.class);
    private final WorkspaceReviewDefaultsProvider workspaceDefaults = mock(WorkspaceReviewDefaultsProvider.class);
    private final FeedbackCompositionResultParser resultParser = mock(FeedbackCompositionResultParser.class);
    private final InAppFeedbackPreparer preparer = mock(InAppFeedbackPreparer.class);
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy = mock(PracticeFeedbackDeliveryPolicy.class);
    private final PreviousInAppFeedback previousInAppFeedback = mock(PreviousInAppFeedback.class);

    private final InAppCompositionListener listener = new InAppCompositionListener(
            agentJobRepository,
            observationRepository,
            feedbackRepository,
            visibilityPolicy,
            workspaceDefaults,
            resultParser,
            preparer,
            deliveryPolicy,
            previousInAppFeedback);

    @Test
    void aNewCardCitesOnlyWorkReviewedAfterThePreviousCardWasResolved() {
        Instant now = Instant.now();
        Instant resolvedAt = now.minus(Duration.ofDays(10));
        Observation beforeTheAnswer = problem(1L, now.minus(Duration.ofDays(20)));
        Observation alsoBeforeTheAnswer = problem(2L, now.minus(Duration.ofDays(14)));
        Observation afterTheAnswer = problem(3L, now.minus(Duration.ofDays(5)));
        Observation alsoAfterTheAnswer = problem(4L, now.minus(Duration.ofDays(3)));
        List<Observation> window = List.of(alsoAfterTheAnswer, afterTheAnswer, alsoBeforeTheAnswer, beforeTheAnswer);
        UUID jobId = composedJob();
        UUID resolved = UUID.randomUUID();
        when(previousInAppFeedback.find(WORKSPACE_ID, RECIPIENT_ID, PRACTICE))
                .thenReturn(Optional.of(
                        new PreviousInAppFeedback.Previous(resolved, now.minus(Duration.ofDays(30)), resolvedAt)));
        windowReadsFrom(window);

        InAppFeedbackPreparer.RoutedMessage message = routedMessage(jobId);

        assertThat(message.decision()).isEqualTo(InAppRoutingDecision.ADMIT);
        assertThat(message.evidence()).containsExactly(alsoAfterTheAnswer, afterTheAnswer);
        assertThat(capturedSince()).isEqualTo(resolvedAt);
        // A resolved card is the record of what was said; the new card is written beside it.
        assertThat(message.replaces()).isNull();
    }

    /**
     * The card still open about the habit is what the new one replaces, and the work it already cited is
     * not cited again: the new card starts where the open one was prepared.
     */
    @Test
    void aNewCardReplacesTheOpenCardAndCitesOnlyWorkSinceItWasPrepared() {
        Instant now = Instant.now();
        Instant preparedAt = now.minus(Duration.ofDays(20));
        Observation citedOnTheOpenCard = problem(1L, now.minus(Duration.ofDays(25)));
        Observation sinceThen = problem(2L, now.minus(Duration.ofDays(9)));
        Observation alsoSinceThen = problem(3L, now.minus(Duration.ofDays(2)));
        UUID jobId = composedJob();
        UUID open = UUID.randomUUID();
        when(previousInAppFeedback.find(WORKSPACE_ID, RECIPIENT_ID, PRACTICE))
                .thenReturn(Optional.of(new PreviousInAppFeedback.Previous(open, preparedAt, null)));
        windowReadsFrom(List.of(alsoSinceThen, sinceThen, citedOnTheOpenCard));

        InAppFeedbackPreparer.RoutedMessage message = routedMessage(jobId);

        assertThat(message.decision()).isEqualTo(InAppRoutingDecision.ADMIT);
        assertThat(message.evidence()).containsExactly(alsoSinceThen, sinceThen);
        assertThat(capturedSince()).isEqualTo(preparedAt);
        assertThat(message.replaces()).isEqualTo(open);
    }

    /**
     * A card older than the window is still the card the new one replaces, but it cannot pull the evidence
     * start back before the window: the read begins at the window start.
     */
    @Test
    void anOpenCardOlderThanTheWindowStillGetsReplacedAndTheReadStartsAtTheWindow() {
        Instant now = Instant.now();
        Instant preparedAt = now.minus(Duration.ofDays(100));
        Observation beforeTheWindow = problem(1L, now.minus(Duration.ofDays(95)));
        Observation inTheWindow = problem(2L, now.minus(Duration.ofDays(40)));
        Observation alsoInTheWindow = problem(3L, now.minus(Duration.ofDays(4)));
        UUID jobId = composedJob();
        UUID open = UUID.randomUUID();
        when(previousInAppFeedback.find(WORKSPACE_ID, RECIPIENT_ID, PRACTICE))
                .thenReturn(Optional.of(new PreviousInAppFeedback.Previous(open, preparedAt, null)));
        windowReadsFrom(List.of(alsoInTheWindow, inTheWindow, beforeTheWindow));

        InAppFeedbackPreparer.RoutedMessage message = routedMessage(jobId);

        assertThat(message.decision()).isEqualTo(InAppRoutingDecision.ADMIT);
        assertThat(message.evidence()).containsExactly(alsoInTheWindow, inTheWindow);
        // The listener reads its own clock, so the edge is the window start to within the test's own tick.
        assertThat(capturedSince())
                .isCloseTo(
                        now.minus(Duration.ofDays(InAppFeedbackRouter.PATTERN_WINDOW_DAYS)),
                        within(10, ChronoUnit.SECONDS));
        assertThat(message.replaces()).isEqualTo(open);
    }

    @Test
    void aHabitNeverAnsweredReadsTheWholeWindow() {
        Instant now = Instant.now();
        Observation old = problem(1L, now.minus(Duration.ofDays(80)));
        Observation recent = problem(2L, now.minus(Duration.ofDays(3)));
        UUID jobId = composedJob();
        when(previousInAppFeedback.find(WORKSPACE_ID, RECIPIENT_ID, PRACTICE)).thenReturn(Optional.empty());
        when(observationRepository.findRecentForSubjectAndPractice(
                        eq(WORKSPACE_ID), eq(RECIPIENT_ID), eq(PRACTICE), any(), any()))
                .thenReturn(List.of(recent, old));

        InAppFeedbackPreparer.RoutedMessage message = routedMessage(jobId);

        assertThat(message.decision()).isEqualTo(InAppRoutingDecision.ADMIT);
        assertThat(message.evidence()).containsExactly(recent, old);
        assertThat(message.replaces()).isNull();
    }

    /** The read honours its lower edge, as the query does. */
    private void windowReadsFrom(List<Observation> window) {
        when(observationRepository.findRecentForSubjectAndPractice(
                        eq(WORKSPACE_ID), eq(RECIPIENT_ID), eq(PRACTICE), any(), any()))
                .thenAnswer(invocation -> {
                    Instant since = invocation.getArgument(3);
                    return window.stream()
                            .filter(observation -> !observation.getObservedAt().isBefore(since))
                            .toList();
                });
    }

    /** The lower edge the lane actually asked the repository for. */
    private Instant capturedSince() {
        ArgumentCaptor<Instant> since = ArgumentCaptor.captor();
        verify(observationRepository)
                .findRecentForSubjectAndPractice(
                        eq(WORKSPACE_ID), eq(RECIPIENT_ID), eq(PRACTICE), since.capture(), any());
        return since.getValue();
    }

    /** Runs the lane for the job and returns the one message it handed the preparer. */
    private InAppFeedbackPreparer.RoutedMessage routedMessage(UUID jobId) {
        listener.prepare(jobId, jobId, WORKSPACE_ID);
        ArgumentCaptor<List<InAppFeedbackPreparer.RoutedMessage>> routed = ArgumentCaptor.captor();
        verify(preparer).prepare(eq(jobId), eq(WORKSPACE_ID), eq(RECIPIENT_ID), routed.capture(), anyInt());
        assertThat(routed.getValue()).hasSize(1);
        return routed.getValue().getFirst();
    }

    /** A finished job that composed one complete IN_APP message about {@link #PRACTICE} for the recipient. */
    private UUID composedJob() {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        when(agentJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(deliveryPolicy.allowsComposition(job, DeliveryPolicySurface.IN_APP))
                .thenReturn(true);
        when(resultParser.parse(any(), eq(FeedbackChannel.IN_APP)))
                .thenReturn(List.of(new ComposedFeedbackUnit(
                        FeedbackChannel.IN_APP,
                        PRACTICE,
                        List.of("obs-1"),
                        ComposedFeedbackUnit.Action.NEW,
                        null,
                        null,
                        "Tests are arriving one commit late",
                        "On your last few changes the test landed a push after the behaviour did.",
                        "Write the assertion before the branch.",
                        null,
                        null)));
        when(observationRepository.findSubjectUserIdsByAgentJobId(job.getId(), WORKSPACE_ID))
                .thenReturn(List.of(RECIPIENT_ID));
        when(workspaceDefaults.forWorkspace(WORKSPACE_ID))
                .thenReturn(new WorkspaceReviewDefaults(PracticeAutonomy.AUTOMATIC));
        when(visibilityPolicy.permitsForNewDelivery(
                        eq(WORKSPACE_ID), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(invocation -> invocation.<List<Observation>>getArgument(1).stream()
                        .map(Observation::getId)
                        .collect(Collectors.toSet()));
        ObservationRepository.ObservationPracticeAutonomy automatic =
                mock(ObservationRepository.ObservationPracticeAutonomy.class);
        when(automatic.getPracticeAutonomy()).thenReturn(PracticeAutonomy.AUTOMATIC);
        when(observationRepository.findPracticeAutonomyFor(any(), eq(WORKSPACE_ID)))
                .thenReturn(List.of(automatic));
        when(feedbackRepository.lastInAppSurfacedAt(WORKSPACE_ID, RECIPIENT_ID, PRACTICE))
                .thenReturn(Optional.empty());
        return job.getId();
    }

    private static Observation problem(long artifactId, Instant observedAt) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.ABSENT)
                .assessment(Assessment.GOOD)
                .origin(ObservationOrigin.LIVE)
                .observedAt(observedAt)
                .build();
    }
}
