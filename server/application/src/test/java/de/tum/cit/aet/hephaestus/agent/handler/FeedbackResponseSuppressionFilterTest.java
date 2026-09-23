package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Unit tests for reaction-aware re-nag suppression (ADR 0021). */
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FeedbackResponseSuppressionFilterTest extends BaseUnitTest {

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ReactionRepository reactionRepository;

    @Mock
    private FeedbackLedgerRecorder feedbackLedgerRecorder;

    private static final String SLUG = "commit-discipline";
    private static final long CONTRIBUTOR = 7L;
    private static final long TARGET = 100L;
    // Both behaviors may share this location, but each reaction remains observation-scoped.
    private static final String CK =
            ObservationFingerprint.compute(SLUG, ArtifactKinds.PULL_REQUEST.value(), TARGET, CONTRIBUTOR, null);

    private FeedbackResponseSuppressionFilter filter(boolean enabled) {
        return new FeedbackResponseSuppressionFilter(
                observationRepository,
                reactionRepository,
                feedbackLedgerRecorder,
                new PracticeReviewProperties(false, 15, 5, enabled, null));
    }

    private AgentJob job() {
        AgentJob job = TestEntities.agentJob();
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void flagOff_passesThroughUnchanged_noRepoCalls() {
        List<ValidatedObservation> in = List.of(vf(SLUG, Presence.ABSENT));

        var d = filter(false).evaluate(job(), in);

        assertThat(d.deliverable()).isEqualTo(in);
        assertThat(d.suppressedCount()).isZero();
        verify(observationRepository, never()).findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void disputedLocus_isSuppressedAndLedgered() {
        stubPersistedAndReaction(FeedbackResolution.DISPUTED);

        var d = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.ABSENT)));

        assertThat(d.deliverable()).isEmpty();
        assertThat(d.suppressedCount()).isEqualTo(1);
        verify(feedbackLedgerRecorder)
                .recordSuppressed(any(), any(), eq(FeedbackSuppressionReason.REACTED_DISPUTED), anyInt());
    }

    @Test
    void suppression_survivesLedgerWriteFailure() {
        stubPersistedAndReaction(FeedbackResolution.NOT_APPLICABLE);
        doThrow(new RuntimeException("ledger down"))
                .when(feedbackLedgerRecorder)
                .recordSuppressed(any(), any(), any(), anyInt());

        var filter = filter(true);
        var job = job();
        var in = List.of(vf(SLUG, Presence.ABSENT));

        assertThatCode(() -> filter.evaluate(job, in)).doesNotThrowAnyException();
        assertThat(filter.evaluate(job, in).deliverable()).isEmpty();
    }

    @Test
    void unreactedLocus_isDelivered() {
        var pf = pf(CK);
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(pf));
        when(reactionRepository.findCurrentResolutionByObservationIds(any(), eq(CONTRIBUTOR), any()))
                .thenReturn(List.of());

        var d = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.ABSENT)));

        assertThat(d.deliverable()).hasSize(1);
        assertThat(d.suppressedCount()).isZero();
    }

    @Test
    void shouldPreserveEvidenceWithoutInventingRecurrenceWhenRecipientMarkedAddressed() {
        stubPersistedAndReaction(FeedbackResolution.ADDRESSED);

        var d = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.ABSENT)));

        assertThat(d.deliverable()).hasSize(1);
        assertThat(d.suppressedCount()).isZero();
        assertThat(d.deliverable().get(0).evidenceRationale()).isEqualTo("because reasons");
    }

    @Test
    void addressedAndNowGood_isDeliveredPlainNotEscalated() {
        stubPersistedAndReaction(FeedbackResolution.ADDRESSED);

        var d = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.PRESENT)));

        assertThat(d.deliverable()).hasSize(1);
        assertThat(d.suppressedCount()).isZero();
        assertThat(d.deliverable().get(0).evidenceRationale()).isEqualTo("because reasons");
    }

    @Test
    void secretBadFinding_isNotSuppressedDespiteDisputedReaction() {
        String secretKey = ObservationFingerprint.compute(
                "avoids-insecure-defaults-and-over-broad-permissions",
                ArtifactKinds.PULL_REQUEST.value(),
                TARGET,
                CONTRIBUTOR,
                null);
        var pf = pf(secretKey);
        var reaction = locus(secretKey, FeedbackResolution.DISPUTED);
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(pf));
        when(reactionRepository.findCurrentResolutionByObservationIds(any(), eq(CONTRIBUTOR), any()))
                .thenReturn(List.of(reaction));

        var d = filter(true).evaluate(job(), List.of(secretScannerObservation(secretKey)));

        assertThat(d.deliverable()).hasSize(1);
        assertThat(d.suppressedCount()).isZero();
        verify(feedbackLedgerRecorder, never()).recordSuppressed(any(), any(), any(), anyInt());
    }

    private static ValidatedObservation secretScannerObservation(@Nullable String recurrenceKey) {
        var evidence = tools.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("detector", "secret-diff-scanner");
        return new ValidatedObservation(
                "avoids-insecure-defaults-and-over-broad-permissions",
                "Hardcoded secret on a changed line",
                AssessmentStatus.ASSESSED,
                Presence.PRESENT,
                Assessment.BAD,
                Severity.CRITICAL,
                evidence,
                "A credential is committed.",
                new ObservationKeys("occ-" + recurrenceKey, recurrenceKey));
    }

    @Test
    void shouldQueryExactObservationWhenLocationIsUnavailable() {
        var pf = pf(null);
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(pf));
        var d = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.ABSENT)));
        assertThat(d.suppressedCount()).isZero();
        verify(reactionRepository)
                .findCurrentResolutionByObservationIds(eq(Set.of(pf.getId())), eq(CONTRIBUTOR), any());
    }

    // --- helpers ---

    private void stubPersistedAndReaction(FeedbackResolution action) {
        var pf = pf(CK);
        var reaction = reaction(action);
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(pf));
        when(reactionRepository.findCurrentResolutionByObservationIds(any(), eq(CONTRIBUTOR), any()))
                .thenReturn(List.of(reaction));
    }

    @Test
    void shouldSuppressOnlyReactedObservationWhenDifferentBehaviorsShareLocation() {
        Observation first = pf(CK, "occ-first");
        Observation second = pf(CK, "occ-second");
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(first, second));
        var disputed = org.mockito.Mockito.mock(ReactionRepository.ObservationResolutionProjection.class);
        UUID reactedId = first.getId();
        when(disputed.getObservationId()).thenReturn(reactedId);
        when(disputed.getResolution()).thenReturn(FeedbackResolution.DISPUTED.name());
        when(reactionRepository.findCurrentResolutionByObservationIds(any(), eq(CONTRIBUTOR), any()))
                .thenReturn(List.of(disputed));
        var otherBehavior = vf(SLUG, Presence.ABSENT, CK, "occ-second");
        var decision = filter(true).evaluate(job(), List.of(vf(SLUG, Presence.ABSENT, CK, "occ-first"), otherBehavior));
        assertThat(decision.deliverable()).containsExactly(otherBehavior);
        assertThat(decision.suppressedCount()).isEqualTo(1);
        verify(feedbackLedgerRecorder)
                .recordSuppressed(any(), eq(first), eq(FeedbackSuppressionReason.REACTED_DISPUTED), anyInt());
    }

    private static UUID id(String occurrence) {
        return UUID.nameUUIDFromBytes(occurrence.getBytes(StandardCharsets.UTF_8));
    }

    private static ValidatedObservation vf(String slug, @Nullable Presence presence) {
        return vf(slug, presence, CK);
    }

    private static ValidatedObservation vf(
            String slug, @Nullable Presence presence, @Nullable String recurrenceKey, String occurrenceKey) {
        return vf(slug, presence, recurrenceKey).withKeys(new ObservationKeys(occurrenceKey, recurrenceKey));
    }

    private static ValidatedObservation vf(String slug, @Nullable Presence presence, @Nullable String recurrenceKey) {
        Assessment assessment = presence == null ? null : Assessment.GOOD;
        // The handler stamps the persisted recurrence_key onto each observation before the filter runs; the filter
        // matches reactions on that stamped key (never a recompute), so the test feeds it the same way.
        return new ValidatedObservation(
                slug,
                slug + " title",
                AssessmentStatus.ASSESSED,
                presence,
                assessment,
                Severity.MINOR,
                null,
                "because reasons",
                new ObservationKeys("occ-" + recurrenceKey, recurrenceKey));
    }

    private Observation pf(@Nullable String recurrenceKey) {
        return pf(recurrenceKey, "occ-" + recurrenceKey);
    }

    private Observation pf(@Nullable String recurrenceKey, String occurrenceKey) {
        Observation pf = org.mockito.Mockito.mock(Observation.class);
        // aboutUserId is always populated; for author-side observations it equals the contributor.
        lenient().when(pf.getRecurrenceKey()).thenReturn(recurrenceKey);
        lenient().when(pf.getOccurrenceKey()).thenReturn(occurrenceKey);
        when(pf.getId()).thenReturn(id(occurrenceKey));
        lenient().when(pf.getAboutUserId()).thenReturn(CONTRIBUTOR);
        return pf;
    }

    private static ReactionRepository.ObservationResolutionProjection reaction(FeedbackResolution resolution) {
        return locus(CK, resolution);
    }

    /** The repository answers with the current response bound to this exact observation. */
    private static ReactionRepository.ObservationResolutionProjection locus(String key, FeedbackResolution resolution) {
        var row = org.mockito.Mockito.mock(ReactionRepository.ObservationResolutionProjection.class);
        when(row.getObservationId()).thenReturn(id("occ-" + key));
        when(row.getResolution()).thenReturn(resolution.name());
        return row;
    }
}
