package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.LocusObservation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.RunRef;
import de.tum.cit.aet.hephaestus.practices.observation.TrendDelta.LocusTransition;
import de.tum.cit.aet.hephaestus.practices.observation.TrendDelta.TransitionStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/** Unit tests for the cross-run trend classifier (ADR 0021, A1). */
class ObservationTrendServiceTest extends BaseUnitTest {

    @Mock
    private ObservationRepository repo;

    @InjectMocks
    private ObservationTrendService service;

    private static final UUID JOB_PREV = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID JOB_CURR = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Long WS = 1L;
    private static final Long TARGET = 100L;

    private void stubTwoTargetRuns() {
        when(repo.findRecentRunRefsForTarget(eq(ArtifactKinds.PULL_REQUEST), eq(TARGET), eq(WS), any()))
                .thenReturn(List.of(
                        runRef(JOB_CURR, Instant.parse("2026-06-15T10:00:00Z")),
                        runRef(JOB_PREV, Instant.parse("2026-06-14T10:00:00Z"))));
    }

    @Test
    void computeForTarget_classifiesNewPersistedResolved() {
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "X title"),
                        // keyY was a PROBLEM (BAD) prior and is gone now → a genuine RESOLVED ("you fixed X").
                        locus(JOB_PREV, "keyY", Presence.ABSENT, Severity.MINOR, "y", "Y title"),
                        locus(JOB_CURR, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "X title v2"),
                        locus(JOB_CURR, "keyZ", Presence.ABSENT, Severity.CRITICAL, "z", "Z title")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.countNew()).isEqualTo(1);
        assertThat(d.countResolved()).isEqualTo(1);
        assertThat(d.countPersisted()).isEqualTo(1);
        assertThat(d.countRegressed()).isZero();
        assertThat(d.hasMeaningfulChange()).isTrue();
        assertThat(status(d, "keyX")).isEqualTo(TransitionStatus.PERSISTED);
        assertThat(status(d, "keyY")).isEqualTo(TransitionStatus.RESOLVED);
        assertThat(status(d, "keyZ")).isEqualTo(TransitionStatus.NEW);
        // a RESOLVED locus carries the PRIOR run's prose (it is absent now; that is what the student last saw)
        LocusTransition resolved = transition(d, "keyY");
        assertThat(resolved.summary()).isEqualTo("Y title");
        assertThat(resolved.priorOutcome()).isEqualTo(Outcome.NEGATIVE);
        assertThat(resolved.currentOutcome()).isNull();
    }

    @Test
    void computeForTarget_vanishedGoodStrengthIsNotRenderedAsResolved() {
        // C10: a GOOD strength that simply was not re-observed this run must NOT render as "Resolved ✓"
        // ("you fixed X" for something already right). It produces no transition at all.
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        // keyG: a satisfied strength (GOOD) prior, absent now → must NOT be RESOLVED.
                        locus(JOB_PREV, "keyG", Presence.PRESENT, Severity.MINOR, "g", "was already satisfied"),
                        // keyB: a real problem (BAD) prior, gone now → a genuine RESOLVED.
                        locus(JOB_PREV, "keyB", Presence.ABSENT, Severity.MAJOR, "b", "was broken")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.countResolved()).isEqualTo(1);
        assertThat(d.resolved().get(0).recurrenceKey()).isEqualTo("keyB");
        assertThat(d.transitions().stream().map(LocusTransition::recurrenceKey)).doesNotContain("keyG");
    }

    @Test
    void computeForTarget_newStrengthIsNotCountedAsNewProblem() {
        // C10: a newly-observed GOOD strength must not inflate countNew (the "N new" problems count).
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        // keyN1: a new strength (GOOD) — present now, absent prior.
                        locus(JOB_CURR, "keyN1", Presence.PRESENT, Severity.MINOR, "g", "newly satisfied"),
                        // keyN2: a new problem (BAD).
                        locus(JOB_CURR, "keyN2", Presence.ABSENT, Severity.MAJOR, "b", "newly broken")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        // Only the BAD-new counts as a "new problem"; the new strength does not.
        assertThat(d.countNew()).isEqualTo(1);
    }

    @Test
    void computeForTarget_nowSatisfiedPersistedLocusIsNotStillOpen() {
        // C10: a BAD->GOOD improvement persists as PERSISTED with currentAssessment=GOOD, and must NOT be
        // counted "still open".
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyS", Presence.ABSENT, Severity.MAJOR, "s", "was broken"),
                        locus(JOB_CURR, "keyS", Presence.PRESENT, Severity.MAJOR, "s", "now satisfied")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(status(d, "keyS")).isEqualTo(TransitionStatus.PERSISTED);
        assertThat(d.countPersisted()).isZero(); // now satisfied → not "still open"
    }

    @Test
    void computeForTarget_allLociPersistUnchanged_isNotMeaningful() {
        // The negative case that guards the silent A4 path: a re-review that only re-flags the same loci with
        // no observation change must NOT count as meaningful change (else it would ping the author about nothing).
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "still broken"),
                        locus(JOB_CURR, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "still broken")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.countPersisted()).isEqualTo(1);
        assertThat(d.countNew() + d.countResolved() + d.countRegressed()).isZero();
        assertThat(d.hasMeaningfulChange()).isFalse();
    }

    @Test
    void computeForTarget_ordersTransitionsRegressedNewResolvedPersisted() {
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "kP", Presence.ABSENT, Severity.MAJOR, "p", "persists"),
                        locus(JOB_CURR, "kP", Presence.ABSENT, Severity.MAJOR, "p", "persists"),
                        locus(JOB_PREV, "kR", Presence.PRESENT, Severity.MAJOR, "r", "was ok"),
                        locus(JOB_CURR, "kR", Presence.ABSENT, Severity.MAJOR, "r", "regressed"),
                        locus(JOB_CURR, "kN", Presence.ABSENT, Severity.MAJOR, "n", "new"),
                        locus(JOB_PREV, "kS", Presence.ABSENT, Severity.MAJOR, "s", "resolved")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.transitions().stream().map(LocusTransition::status).toList())
                .containsExactly(
                        TransitionStatus.REGRESSED,
                        TransitionStatus.NEW,
                        TransitionStatus.RESOLVED,
                        TransitionStatus.PERSISTED);
    }

    private static LocusTransition transition(TrendDelta d, String key) {
        return d.transitions().stream()
                .filter(t -> t.recurrenceKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void computeForTarget_observedThenNotObserved_isRegressed() {
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyX", Presence.PRESENT, Severity.MAJOR, "x", "satisfied last run"),
                        locus(JOB_CURR, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "now broken")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.countRegressed()).isEqualTo(1);
        LocusTransition t = d.transitions().get(0);
        assertThat(t.status()).isEqualTo(TransitionStatus.REGRESSED);
        assertThat(t.priorOutcome()).isEqualTo(Outcome.POSITIVE);
        assertThat(t.currentOutcome()).isEqualTo(Outcome.NEGATIVE);
    }

    @Test
    void computeForTarget_notObservedThenObserved_isImprovementNotRegression() {
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyX", Presence.ABSENT, Severity.MAJOR, "x", "was broken"),
                        locus(JOB_CURR, "keyX", Presence.PRESENT, Severity.MAJOR, "x", "now satisfied")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.countRegressed()).isZero();
        assertThat(d.countResolved()).isZero();
        LocusTransition t = d.transitions().get(0);
        assertThat(t.status()).isEqualTo(TransitionStatus.PERSISTED);
        assertThat(t.currentOutcome()).isEqualTo(Outcome.POSITIVE);
    }

    @Test
    void computeForTarget_duplicateLocusInOneRun_picksWorstSeverityRepresentative() {
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        locus(JOB_PREV, "keyX", Presence.ABSENT, Severity.MINOR, "x", "prior"),
                        // current run emits keyX twice: MAJOR/conf .6 and CRITICAL/conf .9 → CRITICAL must win
                        // (severity over confidence)
                        locusConf(JOB_CURR, "keyX", Presence.ABSENT, Severity.MAJOR, 0.6f, "x", "dup a"),
                        locusConf(JOB_CURR, "keyX", Presence.ABSENT, Severity.CRITICAL, 0.9f, "x", "dup b")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.transitions()).hasSize(1);
        assertThat(d.transitions().get(0).currentSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void computeForTarget_mixedValenceInOneRun_collapsesToBadRepresentative_regressed() {
        // ADR 0022 permits one practice to emit both GOOD and BAD at the same locus in one run. worse()
        // prefers the BAD (non-null severity beats the GOOD's null severity), so a run that contains both
        // collapses to the BAD representative — and a prior all-GOOD run therefore reads as REGRESSED.
        stubTwoTargetRuns();
        when(repo.findLociByAgentJobs(any(), eq(WS)))
                .thenReturn(List.of(
                        // prior run: the locus was a clean strength
                        locusFull(JOB_PREV, "keyM", Presence.PRESENT, Assessment.GOOD, null, 0.9f, "m", "satisfied"),
                        // current run emits the SAME key as both a GOOD (null severity) and a BAD (MAJOR)
                        locusFull(JOB_CURR, "keyM", Presence.PRESENT, Assessment.GOOD, null, 0.9f, "m", "still ok"),
                        locusFull(
                                JOB_CURR,
                                "keyM",
                                Presence.ABSENT,
                                Assessment.GOOD,
                                Severity.MAJOR,
                                0.7f,
                                "m",
                                "regressed")));

        TrendDelta d =
                service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS).orElseThrow();

        assertThat(d.transitions()).hasSize(1);
        LocusTransition t = d.transitions().get(0);
        assertThat(t.status()).isEqualTo(TransitionStatus.REGRESSED);
        assertThat(t.currentOutcome()).isEqualTo(Outcome.NEGATIVE);
        assertThat(t.currentSeverity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    void computeForTarget_singleRun_returnsEmpty() {
        when(repo.findRecentRunRefsForTarget(eq(ArtifactKinds.PULL_REQUEST), eq(TARGET), eq(WS), any()))
                .thenReturn(List.of(runRef(JOB_CURR, Instant.parse("2026-06-15T10:00:00Z"))));

        assertThat(service.computeForTarget(ArtifactKinds.PULL_REQUEST, TARGET, WS))
                .isEmpty();
    }

    private static TransitionStatus status(TrendDelta d, String key) {
        return d.transitions().stream()
                .filter(t -> t.recurrenceKey().equals(key))
                .findFirst()
                .orElseThrow()
                .status();
    }

    // --- projection stubs (anonymous implementations of the repository interface projections) ---

    private static RunRef runRef(UUID job, Instant at) {
        return new RunRef() {
            @Override
            public UUID getAgentJobId() {
                return job;
            }

            @Override
            public Instant getRunAt() {
                return at;
            }
        };
    }

    private static LocusObservation locus(UUID job, String key, Presence v, Severity sev, String slug, String title) {
        return locusConf(job, key, v, sev, 0.8f, slug, title);
    }

    private static LocusObservation locusConf(
            UUID job, String key, Presence v, @Nullable Severity sev, float conf, String slug, String title) {
        // Former-GOOD practices: PRESENT -> GOOD (satisfied), ABSENT -> BAD (problem), NA -> null.
        Assessment assessment =
                switch (v) {
                    case PRESENT -> Assessment.GOOD;
                    case ABSENT -> Assessment.GOOD;
                    case null -> null;
                };
        return locusFull(job, key, v, assessment, sev, conf, slug, title);
    }

    /**
     * Decoupled locus builder: presence and assessment are set independently, so a test can express the
     * ADR-0022 quadrants the presence-coupled helpers cannot (a single locus emitting both a GOOD and a BAD).
     */
    private static LocusObservation locusFull(
            UUID job,
            String key,
            Presence v,
            @Nullable Assessment assessment,
            @Nullable Severity sev,
            float conf,
            String slug,
            String title) {
        return new LocusObservation() {
            @Override
            public AssessmentStatus getAssessmentStatus() {
                return AssessmentStatus.ASSESSED;
            }

            @Override
            public UUID getAgentJobId() {
                return job;
            }

            @Override
            public String getRecurrenceKey() {
                return key;
            }

            @Override
            public Presence getPresence() {
                return v;
            }

            @Override
            public @Nullable Assessment getAssessment() {
                return assessment;
            }

            @Override
            public @Nullable Severity getSeverity() {
                return sev;
            }

            @Override
            public String getPracticeSlug() {
                return slug;
            }

            @Override
            public String getSummary() {
                return title;
            }

            @Override
            public Instant getObservedAt() {
                return Instant.parse("2026-06-15T10:00:00Z");
            }
        };
    }
}
