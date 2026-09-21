package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work.Key;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Combines a practice group's per-practice trends into one direction.
 *
 * <p>A group combines already-computed practice differences using inverse-variance weights. Because practices
 * can share reviewed work, the pooled variance uses the conservative perfect-correlation bound rather than
 * pretending the estimates are independent.
 */
final class GroupTrendAggregator {

    private GroupTrendAggregator() {}

    static PracticeTrend aggregate(
            String groupSlug,
            Collection<String> eligiblePracticeSlugs,
            Collection<PracticeTrend> practiceTrends,
            TrendProperties properties) {
        List<PracticeTrend> comparable = practiceTrends.stream()
                .filter(trend -> trend.difference() != null)
                .toList();
        List<EvidenceOpportunity> trail = mergedTrail(practiceTrends, properties.getBundleSize());
        if (comparable.isEmpty()) {
            int current = distinctOpportunityCount(practiceTrends, TrendBundle.CURRENT);
            int previous = distinctOpportunityCount(practiceTrends, TrendBundle.PREVIOUS);
            int missing = practiceTrends.stream()
                    .mapToInt(trend -> trend.support().opportunitiesUntilComparable())
                    .min()
                    .orElse(properties.getMinBundleSize());
            return new PracticeTrend(
                    groupSlug,
                    TrendScope.GROUP,
                    TrendDirection.INSUFFICIENT_EVIDENCE,
                    TrendSupportFactory.forGroup(
                            properties, current, previous, missing, 0, eligiblePracticeSlugs.size(), trail),
                    null,
                    null,
                    trail,
                    null);
        }

        Pooled pooled = pool(comparable);
        TrendDirection direction = classifyNormal(
                pooled.mean(), pooled.variance(), properties.getRopeHalfWidth(), properties.getCredibilityThreshold());
        return new PracticeTrend(
                groupSlug,
                TrendScope.GROUP,
                direction,
                TrendSupportFactory.forGroup(
                        properties,
                        distinctOpportunityCount(comparable, TrendBundle.CURRENT),
                        distinctOpportunityCount(comparable, TrendBundle.PREVIOUS),
                        0,
                        comparable.size(),
                        eligiblePracticeSlugs.size(),
                        trail),
                summed(comparable, PracticeTrend::currentOutcomes),
                summed(comparable, PracticeTrend::previousOutcomes),
                trail,
                null);
    }

    private record Pooled(double mean, double variance) {}

    private static Pooled pool(List<PracticeTrend> comparable) {
        double weightedMean = 0.0;
        double totalPrecision = 0.0;
        double correlatedStandardDeviation = 0.0;
        for (PracticeTrend trend : comparable) {
            BetaPosterior.Difference difference = Objects.requireNonNull(trend.difference());
            double precision = 1.0 / difference.variance();
            weightedMean += precision * difference.mean();
            totalPrecision += precision;
            // Practices are often reviewed on the same work. Treating their estimates as independent would
            // manufacture confidence, so use the conservative perfect-positive-correlation bound.
            correlatedStandardDeviation += precision * Math.sqrt(difference.variance());
        }
        double standardDeviation = correlatedStandardDeviation / totalPrecision;
        return new Pooled(weightedMean / totalPrecision, standardDeviation * standardDeviation);
    }

    private static OutcomeVector summed(
            List<PracticeTrend> trends, Function<PracticeTrend, @Nullable OutcomeVector> axis) {
        return trends.stream().map(axis).filter(Objects::nonNull).reduce(OutcomeVector.EMPTY, OutcomeVector::plus);
    }

    private static TrendDirection classifyNormal(double mean, double variance, double rope, double threshold) {
        double standardDeviation = Math.sqrt(variance);
        double above = 1.0 - normalCdf((rope - mean) / standardDeviation);
        double below = normalCdf((-rope - mean) / standardDeviation);
        if (above >= threshold) return TrendDirection.IMPROVING;
        if (below >= threshold) return TrendDirection.DECLINING;
        return TrendDirection.UNCERTAIN;
    }

    /** Abramowitz-Stegun 7.1.26: absolute error below 1.5e-7, far under what a 0.90 threshold can notice. */
    private static double normalCdf(double value) {
        double sign = value < 0 ? -1.0 : 1.0;
        double x = Math.abs(value) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double polynomial =
                (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
        double erf = sign * (1.0 - polynomial * Math.exp(-x * x));
        return 0.5 * (1.0 + erf);
    }

    /**
     * One entry per artifact the group saw, whichever of its practices saw it.
     *
     * <p>Two practices reviewing the same pull request is one piece of reviewed work to a reader, so their outcomes are
     * summed into a single point rather than drawn twice. The merged point takes the stronger bundle of the
     * two: an artifact that is current evidence for any practice is current evidence for the group.
     */
    private static List<EvidenceOpportunity> mergedTrail(Collection<PracticeTrend> trends, int bundleSize) {
        Map<Key, EvidenceOpportunity> combined = new LinkedHashMap<>();
        for (PracticeTrend trend : trends) {
            for (EvidenceOpportunity opportunity : trend.opportunities()) {
                combined.merge(Key.of(opportunity), opportunity, (left, right) -> {
                    EvidenceOpportunity newer = left.occurredAt().isAfter(right.occurredAt()) ? left : right;
                    return new EvidenceOpportunity(
                            left.artifactKind(),
                            left.artifactId(),
                            newer.jobId(),
                            newer.occurredAt(),
                            left.outcomes().plus(right.outcomes()),
                            strongerBundle(left.bundle(), right.bundle()));
                });
            }
        }
        List<EvidenceOpportunity> sorted = combined.values().stream()
                .sorted(Comparator.comparing(EvidenceOpportunity::occurredAt))
                .toList();
        return OpportunityBundler.cappedTrail(sorted, bundleSize);
    }

    private static TrendBundle strongerBundle(TrendBundle left, TrendBundle right) {
        if (left == TrendBundle.CURRENT || right == TrendBundle.CURRENT) return TrendBundle.CURRENT;
        if (left == TrendBundle.PREVIOUS || right == TrendBundle.PREVIOUS) return TrendBundle.PREVIOUS;
        return TrendBundle.OLDER;
    }

    private static int distinctOpportunityCount(Collection<PracticeTrend> trends, TrendBundle bundle) {
        return (int) trends.stream()
                .flatMap(trend -> trend.opportunities().stream())
                .filter(opportunity -> opportunity.bundle() == bundle)
                .map(opportunity -> Key.of(opportunity))
                .distinct()
                .count();
    }
}
