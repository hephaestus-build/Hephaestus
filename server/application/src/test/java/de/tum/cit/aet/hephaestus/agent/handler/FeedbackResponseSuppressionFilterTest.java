package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.ObservationResolutionProjection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/** A developer's dispute or "not applicable" holds back feedback about the same claim. */
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FeedbackResponseSuppressionFilterTest extends BaseUnitTest {

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ReactionRepository reactionRepository;

    @Mock
    private FeedbackLedgerRecorder feedbackLedgerRecorder;

    /** What this review recorded so far, in the order the test adds it. */
    private List<Observation> persistedSoFar = List.of();

    private static final String SLUG = "commit-discipline";
    private static final long CONTRIBUTOR = 7L;
    private static final long TARGET = 100L;
    private static final UUID THIS_REVIEW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EARLIER_REVIEW = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant EARLIER = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-02T10:00:00Z");
    private static final String CK =
            ObservationFingerprint.compute(SLUG, ArtifactKinds.PULL_REQUEST.value(), TARGET, CONTRIBUTOR, null);

    private FeedbackResponseSuppressionFilter filter() {
        return new FeedbackResponseSuppressionFilter(observationRepository, reactionRepository, feedbackLedgerRecorder);
    }

    private AgentJob job() {
        AgentJob job = TestEntities.agentJob();
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void shouldSuppressAndLedgerWhenTheFeedbackAboutThisObservationWasDisputed() {
        Observation observation = persisted(CK, "occ-a", Outcome.NOT_MET);
        answers(answer(observation.getId(), THIS_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, LATER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).isEmpty();
        assertThat(decision.suppressedCount()).isEqualTo(1);
        verify(feedbackLedgerRecorder)
                .recordSuppressed(any(), eq(observation), eq(FeedbackSuppressionReason.REACTED_DISPUTED), anyInt());
    }

    @Test
    void shouldStillSuppressWhenTheLedgerWriteFails() {
        Observation observation = persisted(CK, "occ-a", Outcome.NOT_MET);
        answers(answer(
                observation.getId(), THIS_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.NOT_APPLICABLE, LATER));
        doThrow(new RuntimeException("ledger down"))
                .when(feedbackLedgerRecorder)
                .recordSuppressed(any(), any(), any(), anyInt());
        var filter = filter();
        var job = job();
        var in = List.of(vf(CK, "occ-a", Outcome.NOT_MET));

        assertThatCode(() -> filter.evaluate(job, in)).doesNotThrowAnyException();
        assertThat(filter.evaluate(job, in).deliverable()).isEmpty();
    }

    @Test
    void shouldDeliverWhenNothingWasAnswered() {
        persisted(CK, "occ-a", Outcome.NOT_MET);
        answers();

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).hasSize(1);
        assertThat(decision.suppressedCount()).isZero();
    }

    @Test
    void shouldDeliverWithItsEvidenceWhenTheDeveloperMarkedItAddressed() {
        Observation observation = persisted(CK, "occ-a", Outcome.NOT_MET);
        answers(answer(observation.getId(), THIS_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.ADDRESSED, LATER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).hasSize(1);
        assertThat(decision.deliverable().getFirst().evidenceRationale()).isEqualTo("because reasons");
    }

    @Test
    void shouldNeverSuppressACommittedSecretWhenItWasDisputed() {
        String secretKey = ObservationFingerprint.compute(
                "avoids-insecure-defaults-and-over-broad-permissions",
                ArtifactKinds.PULL_REQUEST.value(),
                TARGET,
                CONTRIBUTOR,
                null);
        Observation observation = persisted(secretKey, "occ-" + secretKey, Outcome.NOT_MET);
        answers(answer(
                observation.getId(), THIS_REVIEW, secretKey, Outcome.NOT_MET, FeedbackResolution.DISPUTED, LATER));

        var decision = filter().evaluate(job(), List.of(secretScannerObservation(secretKey)));

        assertThat(decision.deliverable()).hasSize(1);
        verify(feedbackLedgerRecorder, never()).recordSuppressed(any(), any(), any(), anyInt());
    }

    @Test
    void shouldAskOnlyForTheExactObservationWhenItRecordedNoPlace() {
        UUID observationId = persisted(null, "occ-a", Outcome.NOT_MET).getId();
        answers();

        filter().evaluate(job(), List.of(vf(null, "occ-a", Outcome.NOT_MET)));

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(reactionRepository).findCurrentResolutions(eq(1L), eq(List.of(observationId)), keys.capture());
        assertThat(keys.getValue()).isEmpty();
    }

    /** Within one review two observations may share a place and still be two behaviours. */
    @Test
    void shouldSuppressOnlyTheAnsweredObservationWhenTwoInOneReviewShareAPlace() {
        Observation first = persisted(CK, "occ-first", Outcome.NOT_MET);
        persisted(CK, "occ-second", Outcome.NOT_MET);
        answers(answer(first.getId(), THIS_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, LATER));
        var otherBehaviour = vf(CK, "occ-second", Outcome.NOT_MET);

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-first", Outcome.NOT_MET), otherBehaviour));

        assertThat(decision.deliverable()).containsExactly(otherBehaviour);
        verify(feedbackLedgerRecorder)
                .recordSuppressed(any(), eq(first), eq(FeedbackSuppressionReason.REACTED_DISPUTED), anyInt());
    }

    @Test
    void shouldSuppressTheSameClaimWhenAnEarlierReviewOfTheWorkWasDisputed() {
        Observation observation = persisted(CK, "occ-a", Outcome.NOT_MET);
        answers(answer(UUID.randomUUID(), EARLIER_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, EARLIER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).isEmpty();
        verify(feedbackLedgerRecorder)
                .recordSuppressed(any(), eq(observation), eq(FeedbackSuppressionReason.REACTED_DISPUTED), anyInt());
    }

    /** A dispute of "this is missing" says nothing about a later "this is present" at the same place. */
    @Test
    void shouldDeliverADifferentClaimAtTheSamePlaceWhenAnEarlierOneWasDisputed() {
        persisted(CK, "occ-a", Outcome.MET);
        answers(answer(UUID.randomUUID(), EARLIER_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, EARLIER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.MET)));

        assertThat(decision.deliverable()).hasSize(1);
    }

    @Test
    void shouldDeliverWhenANewerAnswerAboutTheClaimNoLongerDisputesIt() {
        persisted(CK, "occ-a", Outcome.NOT_MET);
        answers(
                answer(UUID.randomUUID(), EARLIER_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, EARLIER),
                answer(UUID.randomUUID(), EARLIER_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.ADDRESSED, LATER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).hasSize(1);
    }

    @Test
    void shouldDeliverADifferentStatementWithTheSameOutcomeAtTheSamePlace() {
        Observation current = persisted(CK, "occ-a", Outcome.NOT_MET);
        when(current.getSummary()).thenReturn("The test does not cover the failure path");
        answers(answer(UUID.randomUUID(), EARLIER_REVIEW, CK, Outcome.NOT_MET, FeedbackResolution.DISPUTED, EARLIER));

        var decision = filter().evaluate(job(), List.of(vf(CK, "occ-a", Outcome.NOT_MET)));

        assertThat(decision.deliverable()).hasSize(1);
    }

    // --- helpers ---

    private Observation persisted(@Nullable String recurrenceKey, String occurrenceKey, Outcome outcome) {
        Observation observation = mock(Observation.class);
        lenient().when(observation.getRecurrenceKey()).thenReturn(recurrenceKey);
        lenient().when(observation.getOccurrenceKey()).thenReturn(occurrenceKey);
        lenient().when(observation.getId()).thenReturn(id(occurrenceKey));
        lenient().when(observation.getAgentJobId()).thenReturn(THIS_REVIEW);
        lenient().when(observation.getOutcome()).thenReturn(outcome);
        lenient().when(observation.getSummary()).thenReturn(SLUG + " title");
        lenient().when(observation.getAboutUserId()).thenReturn(CONTRIBUTOR);
        List<Observation> all = new java.util.ArrayList<>(persistedSoFar);
        all.add(observation);
        persistedSoFar = all;
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(all);
        return observation;
    }

    private void answers(ObservationResolutionProjection... rows) {
        when(reactionRepository.findCurrentResolutions(anyLong(), any(), any())).thenReturn(List.of(rows));
    }

    private static ObservationResolutionProjection answer(
            UUID observationId,
            UUID agentJobId,
            String recurrenceKey,
            Outcome outcome,
            FeedbackResolution resolution,
            Instant respondedAt) {
        var row = mock(ObservationResolutionProjection.class);
        when(row.getObservationId()).thenReturn(observationId);
        when(row.getAgentJobId()).thenReturn(agentJobId);
        when(row.getRecurrenceKey()).thenReturn(recurrenceKey);
        when(row.getOutcome()).thenReturn(outcome.name());
        when(row.getSummary()).thenReturn(SLUG + " title");
        when(row.getResolution()).thenReturn(resolution.name());
        when(row.getRespondedAt()).thenReturn(respondedAt);
        return row;
    }

    private static UUID id(String occurrence) {
        return UUID.nameUUIDFromBytes(occurrence.getBytes(StandardCharsets.UTF_8));
    }

    private static ValidatedObservation vf(@Nullable String recurrenceKey, String occurrenceKey, Outcome outcome) {
        return new ValidatedObservation(
                SLUG,
                SLUG + " title",
                outcome,
                outcome == Outcome.NOT_MET ? Severity.MINOR : null,
                null,
                "because reasons",
                new ObservationKeys(occurrenceKey, recurrenceKey));
    }

    private static ValidatedObservation secretScannerObservation(String recurrenceKey) {
        var evidence = tools.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("detector", "secret-diff-scanner");
        return new ValidatedObservation(
                "avoids-insecure-defaults-and-over-broad-permissions",
                "Hardcoded secret on a changed line",
                Outcome.NOT_MET,
                Severity.CRITICAL,
                evidence,
                "A credential is committed.",
                new ObservationKeys("occ-" + recurrenceKey, recurrenceKey));
    }
}
