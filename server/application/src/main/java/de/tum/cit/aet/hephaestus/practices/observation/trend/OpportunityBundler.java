package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ReviewedWorkKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Converts raw observations into opportunity-indexed, non-overlapping comparison bundles. */
final class OpportunityBundler {

    private OpportunityBundler() {}

    static Bundles bundle(List<Observation> observations, Instant cutoff, int bundleSize) {
        List<EvidenceOpportunity> all = opportunities(observations, cutoff);
        List<EvidenceOpportunity> applicable =
                all.stream().filter(EvidenceOpportunity::applicable).toList();
        List<EvidenceOpportunity> current =
                tagged(applicable.stream().limit(bundleSize).toList(), TrendBundle.CURRENT);
        List<EvidenceOpportunity> previous =
                tagged(applicable.stream().skip(bundleSize).limit(bundleSize).toList(), TrendBundle.PREVIOUS);
        Map<ReviewedWorkKey, TrendBundle> bundleByArtifact = new LinkedHashMap<>();
        current.forEach(opportunity -> bundleByArtifact.put(opportunity.key(), TrendBundle.CURRENT));
        previous.forEach(opportunity -> bundleByArtifact.put(opportunity.key(), TrendBundle.PREVIOUS));
        List<EvidenceOpportunity> trail = all.stream()
                .map(opportunity ->
                        opportunity.withBundle(bundleByArtifact.getOrDefault(opportunity.key(), TrendBundle.OLDER)))
                .sorted(Comparator.comparing(EvidenceOpportunity::occurredAt))
                .toList();
        return new Bundles(current, previous, trail);
    }

    /**
     * One opportunity per piece of reviewed work observed at or after {@code cutoff}, read off its latest run,
     * newest first.
     */
    static List<EvidenceOpportunity> opportunities(List<Observation> observations, Instant cutoff) {
        Map<ReviewedWorkKey, List<Observation>> byArtifact = new LinkedHashMap<>();
        observations.stream()
                .filter(observation -> !observation.getObservedAt().isBefore(cutoff))
                .forEach(observation -> byArtifact
                        .computeIfAbsent(ReviewedWorkKey.of(observation), ignored -> new ArrayList<>())
                        .add(observation));
        return byArtifact.entrySet().stream()
                .map(entry -> latestRunOpportunity(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(EvidenceOpportunity::occurredAt)
                        .thenComparing(opportunity -> opportunity.artifactKind().value())
                        .thenComparingLong(EvidenceOpportunity::artifactId)
                        .reversed())
                .toList();
    }

    private static EvidenceOpportunity latestRunOpportunity(ReviewedWorkKey artifact, List<Observation> observations) {
        UUID latestJob = LatestRun.of(observations);
        List<Observation> latest = observations.stream()
                .filter(row -> latestJob.equals(row.getAgentJobId()))
                .toList();
        OutcomeVector outcomes = latest.stream()
                .map(row -> OutcomeVector.of(row.getAssessmentStatus(), row.getPresence(), row.getAssessment()))
                .reduce(OutcomeVector.EMPTY, OutcomeVector::plus);
        Instant occurredAt = latest.stream()
                .map(Observation::getObservedAt)
                .max(Instant::compareTo)
                .orElseThrow();
        return new EvidenceOpportunity(
                artifact.kind(), artifact.id(), latestJob, occurredAt, outcomes, TrendBundle.OLDER);
    }

    /**
     * Keeps the newest opportunities that still SAID something, plus whatever fell between them.
     *
     * <p>The cap bounds what a sparkline has to draw, so it counts the opportunities a reader can see a
     * verdict in. Counting rows instead would let a stretch of work that offered this practice no opportunity
     * push real evidence out of the window — and the standing reads its window off this same list, so that
     * would quietly shrink the sample a standing rests on rather than merely shortening a chart.
     */
    static List<EvidenceOpportunity> cappedTrail(List<EvidenceOpportunity> trail, int bundleSize) {
        int cap = 2 * bundleSize + 4;
        int applicable = 0;
        for (int index = trail.size() - 1; index >= 0; index--) {
            if (trail.get(index).applicable()) {
                applicable++;
            }
            if (applicable == cap) {
                return trail.subList(index, trail.size());
            }
        }
        return trail;
    }

    private static List<EvidenceOpportunity> tagged(List<EvidenceOpportunity> opportunities, TrendBundle bundle) {
        return opportunities.stream()
                .map(opportunity -> opportunity.withBundle(bundle))
                .toList();
    }

    record Bundles(
            List<EvidenceOpportunity> current, List<EvidenceOpportunity> previous, List<EvidenceOpportunity> trail) {
        int opportunitiesUntilComparable(int minimumBundleSize) {
            return Math.max(0, minimumBundleSize - previous.size());
        }
    }
}
