package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * The in-context delivery predicate: which observations are allowed to land on the artifact, and what is
 * written down about the ones that are not. Two rules apply — the practice's autonomy and the run's
 * provenance — and this holds both to their reasons.
 */
@DisplayName("In-context delivery admission")
class InContextDeliveryGateTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 4L;
    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FeedbackLedgerRecorder feedbackLedgerRecorder;

    private InContextDeliveryGate gate() {
        return new InContextDeliveryGate(
                practiceRepository,
                observationRepository,
                feedbackLedgerRecorder,
                InContextDeliveryGateFixtures.workspaceDefaults(),
                InContextDeliveryGateFixtures.workspacesAtTheDefaultJobRevision());
    }

    @Test
    void aJobAdmittedUnderAnOlderRolloutSaysNothingOnTheArtifact() {
        InContextDeliveryGate gate = new InContextDeliveryGate(
                practiceRepository,
                observationRepository,
                feedbackLedgerRecorder,
                InContextDeliveryGateFixtures.workspaceDefaults(),
                InContextDeliveryGateFixtures.workspacesAtRevision(7L));
        List<Observation> persisted = List.of(observation("occ-1"));
        when(observationRepository.findByAgentJobId(JOB_ID, WORKSPACE_ID)).thenReturn(persisted);

        assertThat(gate.admitInContext(job(), List.of(observation("loud", "occ-1"))))
                .isEmpty();
        assertThat(gate.awaitingApproval(job(), List.of(observation("loud", "occ-1"))))
                .isEmpty();
        verify(feedbackLedgerRecorder)
                .recordWithheld(any(), any(), eq(FeedbackSuppressionReason.STALE_ROLLOUT_REVISION), anyInt());
    }

    @Test
    void aWorkspaceThatNoLongerExistsProducesNoInContextFeedback() {
        InContextDeliveryGate gate = new InContextDeliveryGate(
                practiceRepository,
                observationRepository,
                feedbackLedgerRecorder,
                InContextDeliveryGateFixtures.workspaceDefaults(),
                InContextDeliveryGateFixtures.noWorkspaces());

        assertThat(gate.admitInContext(job(), List.of(observation("loud", "occ-1"))))
                .isEmpty();
    }

    @Test
    void deliveringPracticesReachTheArtifact() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(practice("loud", PracticeAutonomy.AUTOMATIC)));
        ValidatedObservation observation = observation("loud", "occ-1");

        assertThat(gate().admitInContext(job(), List.of(observation))).containsExactly(observation);
        verifyNoInteractions(feedbackLedgerRecorder);
    }

    @Test
    void proposingPracticesAreWithheldFromTheArtifact() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(
                        practice("measured", PracticeAutonomy.HUMAN_APPROVAL),
                        practice("proposed", PracticeAutonomy.HUMAN_APPROVAL),
                        practice("loud", PracticeAutonomy.AUTOMATIC)));
        ValidatedObservation measured = observation("measured", "occ-1");
        ValidatedObservation proposed = observation("proposed", "occ-2");
        ValidatedObservation loud = observation("loud", "occ-3");
        List<ValidatedObservation> admitted = gate().admitInContext(job(), List.of(measured, proposed, loud));

        assertThat(admitted).containsExactly(loud);
        assertThat(gate().awaitingApproval(job(), List.of(measured, proposed, loud)))
                .containsExactly(measured, proposed);
        verifyNoInteractions(feedbackLedgerRecorder);
    }

    /**
     * A slug the workspace catalogue does not contain was never persisted as an observation either, so
     * there is no autonomy to consult; withholding it would silently drop feedback on a lookup miss.
     */
    @Test
    void anUnknownPracticeSlugFailsClosed() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(practice("known", PracticeAutonomy.HUMAN_APPROVAL)));
        ValidatedObservation stranger = observation("not-in-the-catalogue", "occ-9");

        assertThat(gate().admitInContext(job(), List.of(stranger))).isEmpty();
        verify(feedbackLedgerRecorder, never()).recordWithheld(any(), any(), any(), anyInt());
    }

    @Test
    void aWithheldObservationThatWasNeverPersistedGetsNoLedgerRow() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(practice("measured", PracticeAutonomy.OFF)));
        when(observationRepository.findByAgentJobId(JOB_ID, WORKSPACE_ID)).thenReturn(List.of());

        assertThat(gate().admitInContext(job(), List.of(observation("measured", null))))
                .isEmpty();
        verify(feedbackLedgerRecorder, never()).recordWithheld(any(), any(), any(), anyInt());
    }

    /** A ledger failure is telemetry loss, never delivery loss: the surviving observations still go out. */
    @Test
    void aLedgerFailureDoesNotStopTheFindingsThatSurvived() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(
                        practice("measured", PracticeAutonomy.OFF), practice("loud", PracticeAutonomy.AUTOMATIC)));
        List<Observation> persisted = List.of(observation("occ-1"));
        when(observationRepository.findByAgentJobId(JOB_ID, WORKSPACE_ID)).thenReturn(persisted);
        doThrow(new IllegalStateException("ledger down"))
                .when(feedbackLedgerRecorder)
                .recordWithheld(any(), any(), any(), eq(0));
        ValidatedObservation loud = observation("loud", "occ-2");

        assertThat(gate().admitInContext(job(), List.of(observation("measured", "occ-1"), loud)))
                .containsExactly(loud);
    }

    /**
     * A campaign's observations never reach the artifact. Posting one would comment on a pull request merged
     * months ago and notify everyone still subscribed to it about work nobody can act on.
     */
    @Test
    void aBackfilledRunSaysNothingOnTheArtifactWhateverTheTierIs() {
        List<Observation> persisted = List.of(observation("occ-1"), observation("occ-2"));
        when(observationRepository.findByAgentJobId(JOB_ID, WORKSPACE_ID)).thenReturn(persisted);
        ValidatedObservation loud = observation("loud", "occ-1");
        ValidatedObservation alsoLoud = observation("also-loud", "occ-2");

        assertThat(gate().admitInContext(backfillJob(), List.of(loud, alsoLoud)))
                .isEmpty();
        // Never even asks for the autonomy states: no dial can make a retrospective observation actionable in place.
        verifyNoInteractions(practiceRepository);
        verify(feedbackLedgerRecorder, org.mockito.Mockito.times(2))
                .recordWithheld(any(), any(), eq(FeedbackSuppressionReason.BACKFILL_QUIET), anyInt());
    }

    /** The two withholding rules are separately answerable, which is why they are separately recorded. */
    @Test
    void aAutonomyWithheldObservationIsRecordedUnderTheTierNotTheProvenance() {
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(practice("measured", PracticeAutonomy.OFF)));
        List<Observation> persisted = List.of(observation("occ-1"));
        when(observationRepository.findByAgentJobId(JOB_ID, WORKSPACE_ID)).thenReturn(persisted);

        assertThat(gate().admitInContext(job(), List.of(observation("measured", "occ-1"))))
                .isEmpty();
        verify(feedbackLedgerRecorder)
                .recordWithheld(any(), any(), eq(FeedbackSuppressionReason.PRACTICE_REQUIRES_APPROVAL), eq(0));
    }

    @Test
    void aJobWithoutAWorkspaceIsLeftAlone() {
        AgentJob job = new AgentJob();
        job.setId(JOB_ID);
        ValidatedObservation observation = observation("anything", "occ-1");

        assertThat(gate().admitInContext(job, List.of(observation))).containsExactly(observation);
        verifyNoInteractions(practiceRepository);
    }

    /** A job stamped the way {@code ReviewBackfillSubmitter} stamps one. */
    private AgentJob backfillJob() {
        AgentJob job = job();
        job.setMetadata(new tools.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put(PracticeDetectionDeliveryService.ORIGIN_METADATA_KEY, ObservationOrigin.BACKFILL.name()));
        return job;
    }

    private AgentJob job() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        AgentJob job = new AgentJob();
        job.setId(JOB_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private Practice practice(String slug, PracticeAutonomy autonomy) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        practice.setAutonomy(autonomy);
        return practice;
    }

    private ValidatedObservation observation(String slug, @Nullable String occurrenceKey) {
        return new ValidatedObservation(
                slug,
                "title",
                AssessmentStatus.ASSESSED,
                Presence.ABSENT,
                Assessment.BAD,
                Severity.MAJOR,
                null,
                "reasoning",
                occurrenceKey == null ? null : new ObservationKeys(occurrenceKey, "rk-" + occurrenceKey));
    }

    private Observation observation(@Nullable String occurrenceKey) {
        Observation observation = org.mockito.Mockito.mock(Observation.class);
        org.mockito.Mockito.lenient().when(observation.getOccurrenceKey()).thenReturn(occurrenceKey);
        return observation;
    }
}
