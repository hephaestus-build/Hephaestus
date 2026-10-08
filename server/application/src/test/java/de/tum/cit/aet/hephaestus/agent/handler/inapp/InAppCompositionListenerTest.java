package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class InAppCompositionListenerTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 9L;
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final List<String> SLUGS = List.of("record-outcomes", "ship-tests");
    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final ObservationRepository observations = mock(ObservationRepository.class);
    private final FeedbackRepository feedback = mock(FeedbackRepository.class);
    private final ObservationVisibilityPolicy visibility = mock(ObservationVisibilityPolicy.class);
    private final WorkspaceReviewDefaultsProvider defaults = mock(WorkspaceReviewDefaultsProvider.class);
    private final FeedbackCompositionResultParser parser = mock(FeedbackCompositionResultParser.class);
    private final PracticeFeedbackDeliveryPolicy policy = mock(PracticeFeedbackDeliveryPolicy.class);
    private final PreviousInAppFeedback previous = mock(PreviousInAppFeedback.class);
    private final InAppFeedbackPreparer preparer = new InAppFeedbackPreparer(
            feedback, mock(FeedbackObservationRepository.class), mock(FeedbackSupersession.class));
    private final InAppCompositionListener listener = new InAppCompositionListener(
            jobs,
            observations,
            feedback,
            new StaticListableBeanFactory(Map.of(
                            "supportReader",
                            new InAppSupportReader(
                                    observations, visibility, previous, policy, Clock.fixed(NOW, ZoneOffset.UTC))))
                    .getBeanProvider(InAppSupportReader.class),
            defaults,
            parser,
            preparer,
            policy,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(
            value = PracticeAutonomy.class,
            names = {"AUTOMATIC", "HUMAN_APPROVAL"})
    void shouldKeepRecipientPositionsWhenAnotherRecipientIsRefused(PracticeAutonomy autonomy) {
        AgentJob source = setup();
        UUID outputId = UUID.randomUUID();
        AgentJob output = new AgentJob();
        output.setId(outputId);
        when(jobs.findById(outputId)).thenReturn(Optional.of(output));
        when(observations.findSubjectUserIdsByAgentJobId(source.getId(), WORKSPACE_ID))
                .thenReturn(Arrays.asList(null, 21L, 22L, 23L));
        allow(source, 21L);
        refuse(source, 22L, FeedbackSuppressionReason.ARTIFACT_GONE);
        allow(source, 23L);
        evidenceFor(21L, autonomy);
        evidenceFor(23L, autonomy);

        assertThat(listener.prepare(source.getId(), outputId, WORKSPACE_ID)).isEqualTo(4);

        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedback, times(4)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Feedback::getRecipientUserId)
                .containsExactly(21L, 21L, 23L, 23L);
        assertThat(saved.getAllValues())
                .extracting(Feedback::getPosition)
                .containsExactly(
                        FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE,
                        FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE + 1,
                        FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE + 4,
                        FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE + 5);
        assertThat(saved.getAllValues()).allSatisfy(row -> {
            assertThat(row.getAgentJobId()).isEqualTo(outputId);
            assertThat(row.getChannel()).isEqualTo(FeedbackChannel.IN_APP);
            assertThat(row.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        });
        verify(jobs).markInAppPrepared(outputId, NOW);
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackSuppressionReason.class,
            names = {"RECIPIENT_OPTED_OUT", "ARTIFACT_GONE"})
    void shouldPrepareNothingWhenTheRecipientIsNoLongerEligible(FeedbackSuppressionReason reason) {
        AgentJob source = setup();
        when(observations.findSubjectUserIdsByAgentJobId(source.getId(), WORKSPACE_ID))
                .thenReturn(List.of(21L));
        refuse(source, 21L, reason);

        assertThat(listener.prepare(source.getId(), WORKSPACE_ID)).isZero();

        verify(feedback, never()).save(any());
        verify(jobs).markInAppPrepared(source.getId(), NOW);
    }

    private AgentJob setup() {
        AgentJob source = new AgentJob();
        source.setId(UUID.randomUUID());
        when(jobs.findById(source.getId())).thenReturn(Optional.of(source));
        when(policy.allowsComposition(source, DeliveryPolicySurface.IN_APP)).thenReturn(true);
        when(parser.parse(null, FeedbackChannel.IN_APP))
                .thenReturn(SLUGS.stream()
                        .map(slug -> new ComposedFeedbackUnit(
                                FeedbackChannel.IN_APP,
                                slug,
                                List.of(),
                                ComposedFeedbackUnit.Action.NEW,
                                null,
                                null,
                                "Record the outcome",
                                "The outcome is missing on separate pieces of work.",
                                "Record the next outcome.",
                                null))
                        .toList());
        return source;
    }

    private void allow(AgentJob source, long recipient) {
        when(policy.evaluateForRecipient(
                        source,
                        DeliveryPolicyStage.COMPOSITION,
                        null,
                        DeliveryPolicySurface.IN_APP,
                        recipient,
                        List.of()))
                .thenReturn(new PracticeFeedbackDeliveryPolicy.DeliveryDecision(true, null));
    }

    private void refuse(AgentJob source, long recipient, FeedbackSuppressionReason reason) {
        when(policy.evaluateForRecipient(
                        source,
                        DeliveryPolicyStage.COMPOSITION,
                        null,
                        DeliveryPolicySurface.IN_APP,
                        recipient,
                        List.of()))
                .thenReturn(new PracticeFeedbackDeliveryPolicy.DeliveryDecision(false, reason));
    }

    private void evidenceFor(long recipient, PracticeAutonomy tier) {
        when(defaults.forWorkspace(WORKSPACE_ID)).thenReturn(new WorkspaceReviewDefaults(PracticeAutonomy.AUTOMATIC));
        when(feedback.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        for (String slug : SLUGS) {
            List<Observation> evidence = List.of(problem(1L), problem(2L));
            when(observations.findRecentForSubjectAndPractice(eq(WORKSPACE_ID), eq(recipient), eq(slug), any(), any()))
                    .thenReturn(evidence);
            when(visibility.permitsForNewDelivery(eq(WORKSPACE_ID), eq(evidence), any()))
                    .thenReturn(evidence.stream().map(Observation::getId).collect(Collectors.toSet()));
            ObservationRepository.ObservationPracticeAutonomy autonomy =
                    mock(ObservationRepository.ObservationPracticeAutonomy.class);
            when(autonomy.getPracticeAutonomy()).thenReturn(tier);
            when(observations.findPracticeAutonomyFor(
                            evidence.stream().map(Observation::getId).toList(), WORKSPACE_ID))
                    .thenReturn(List.of(autonomy));
        }
    }

    private Observation problem(long artifact) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .artifactKind(ArtifactKinds.ISSUE)
                .artifactId(artifact)
                .origin(ObservationOrigin.LIVE)
                .outcome(Outcome.NOT_MET)
                .observedAt(NOW)
                .build();
    }
}
