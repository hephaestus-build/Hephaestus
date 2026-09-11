package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OpportunityBundlerTest {

    @Test
    void shouldKeepOnlyLatestRunWithinAnArtifact() {
        UUID older = UUID.randomUUID();
        UUID latest = UUID.randomUUID();

        OpportunityBundler.Bundles result = bundle(
                observation(1, older, "2026-08-10T09:00:00Z", Assessment.BAD),
                observation(1, latest, "2026-08-11T09:00:00Z", Assessment.GOOD));

        assertThat(result.current()).hasSize(1);
        assertThat(result.current().getFirst().outcomes().demonstratedStrengths())
                .isEqualTo(1);
        assertThat(result.current().getFirst().outcomes().commissionProblems()).isZero();
    }

    @Test
    void shouldTreatBurstySameDayArtifactsAsSeparateOpportunities() {
        OpportunityBundler.Bundles result = bundle(
                observation(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD),
                observation(2, UUID.randomUUID(), "2026-08-11T09:01:00Z", Assessment.GOOD),
                observation(3, UUID.randomUUID(), "2026-08-11T09:02:00Z", Assessment.BAD));

        assertThat(result.current()).hasSize(3);
    }

    @Test
    void shouldNotTurnCalendarGapIntoABundleBoundary() {
        OpportunityBundler.Bundles result = bundle(
                observation(1, UUID.randomUUID(), "2026-07-01T09:00:00Z", Assessment.BAD),
                observation(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD));

        assertThat(result.current()).hasSize(2);
        assertThat(result.previous()).isEmpty();
    }

    @Test
    void shouldUseStableArtifactOrderWhenTimestampsTie() {
        Observation first = observation(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD);
        Observation second = observation(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.BAD);

        OpportunityBundler.Bundles forward =
                OpportunityBundler.bundle(List.of(first, second), Instant.parse("2026-05-01T00:00:00Z"), 1);
        OpportunityBundler.Bundles reversed =
                OpportunityBundler.bundle(List.of(second, first), Instant.parse("2026-05-01T00:00:00Z"), 1);

        assertThat(forward.current().getFirst().artifactId()).isEqualTo(2);
        assertThat(reversed.current().getFirst().artifactId()).isEqualTo(2);
    }

    @Test
    void shouldDiscardOpportunitiesOutsideTheHorizon() {
        OpportunityBundler.Bundles result = OpportunityBundler.bundle(
                List.of(
                        observation(1, UUID.randomUUID(), "2026-01-01T09:00:00Z", Assessment.BAD),
                        observation(2, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD)),
                Instant.parse("2026-05-01T00:00:00Z"),
                4);

        assertThat(result.trail()).hasSize(1);
    }

    @Test
    void shouldReportHowManyMoreOpportunitiesEnableComparison() {
        OpportunityBundler.Bundles result = bundle(
                observation(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD),
                observation(2, UUID.randomUUID(), "2026-08-11T10:00:00Z", Assessment.GOOD),
                observation(3, UUID.randomUUID(), "2026-08-11T11:00:00Z", Assessment.BAD),
                observation(4, UUID.randomUUID(), "2026-08-11T12:00:00Z", Assessment.BAD),
                observation(5, UUID.randomUUID(), "2026-08-11T13:00:00Z", Assessment.BAD));

        assertThat(result.current()).hasSize(4);
        assertThat(result.previous()).hasSize(1);
        assertThat(result.opportunitiesUntilComparable(3)).isEqualTo(2);
    }

    @Test
    void shouldKeepAnOpportunityThatProducedNoVerdictOutOfTheBundles() {
        // It stays in the trail: the practice looked at this piece of reviewed work, which is a different fact from
        // never
        // having reviewed it. But it carries no verdict, so it must not become a sample.
        OpportunityBundler.Bundles result = bundle(
                observation(1, UUID.randomUUID(), "2026-08-11T09:00:00Z", Assessment.GOOD),
                inapplicable(2, UUID.randomUUID(), "2026-08-11T10:00:00Z"),
                observation(3, UUID.randomUUID(), "2026-08-11T11:00:00Z", Assessment.BAD));

        assertThat(result.trail()).hasSize(3);
        assertThat(result.current()).hasSize(2);
        assertThat(result.current())
                .noneMatch(opportunity -> opportunity.outcomes().notApplicable() > 0);
    }

    @Test
    void shouldNotLetAVerdictlessRunSupersedeAnEarlierVerdictOnTheSameArtifact() {
        // Same artifact, later run found nothing to judge. The latest run still wins — a re-review that says
        // the practice no longer applies here must not leave the old problem standing.
        OpportunityBundler.Bundles result = bundle(
                observation(1, UUID.randomUUID(), "2026-08-10T09:00:00Z", Assessment.BAD),
                inapplicable(1, UUID.randomUUID(), "2026-08-11T09:00:00Z"));

        assertThat(result.trail()).hasSize(1);
        assertThat(result.trail().getFirst().applicable()).isFalse();
        assertThat(result.current()).isEmpty();
    }

    private static OpportunityBundler.Bundles bundle(Observation... observations) {
        return OpportunityBundler.bundle(List.of(observations), Instant.parse("2026-05-01T00:00:00Z"), 4);
    }

    private static Observation observation(long artifactId, UUID jobId, String observedAt, Assessment assessment) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(jobId)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(assessment)
                .observedAt(Instant.parse(observedAt))
                .build();
    }

    private static Observation inapplicable(long artifactId, UUID jobId, String observedAt) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(jobId)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(artifactId)
                .assessmentStatus(AssessmentStatus.NOT_APPLICABLE)
                .presence(null)
                .observedAt(Instant.parse(observedAt))
                .build();
    }
}
