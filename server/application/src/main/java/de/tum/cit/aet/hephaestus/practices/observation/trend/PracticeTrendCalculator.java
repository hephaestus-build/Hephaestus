package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.time.Instant;
import java.util.List;

/**
 * Pure opportunity-indexed Bayesian trend calculator for one practice.
 *
 * <p>Calendar units are never samples: bursty repository activity is ordered by evidence opportunity, while
 * timestamps survive only as provenance. Recalculation after every event is legitimate because this is a
 * Bayesian posterior, not a repeatedly peeked frequentist test. The package javadoc carries the grounding for
 * both halves of that sentence.
 *
 * <p>Aggregating several of these into a group is a different estimator and lives in
 * {@link GroupTrendAggregator}.
 */
final class PracticeTrendCalculator {

    private PracticeTrendCalculator() {}

    static PracticeTrend calculatePractice(
            String practiceSlug, List<Observation> observations, Instant cutoff, TrendProperties properties) {
        OpportunityBundler.Bundles bundles =
                OpportunityBundler.bundle(observations, cutoff, properties.getBundleSize());
        int missing = bundles.opportunitiesUntilComparable(properties.getBundleSize(), properties.getMinBundleSize());
        List<EvidenceOpportunity> trail = OpportunityBundler.cappedTrail(bundles.trail(), properties.getBundleSize());
        TrendSupport support = TrendSupportFactory.forPractice(
                properties, bundles.current().size(), bundles.previous().size(), missing, trail);
        if (missing > 0) {
            return new PracticeTrend(
                    practiceSlug,
                    TrendScope.PRACTICE,
                    TrendDirection.INSUFFICIENT_EVIDENCE,
                    support,
                    null,
                    null,
                    trail,
                    null);
        }

        BetaPosterior.Difference difference =
                posterior(bundles.current()).differenceFrom(posterior(bundles.previous()));
        TrendDirection direction = TrendDirectionRule.classify(
                difference, properties.getRopeHalfWidth(), properties.getCredibilityThreshold());
        return new PracticeTrend(
                practiceSlug,
                TrendScope.PRACTICE,
                direction,
                support,
                outcomes(bundles.current()),
                outcomes(bundles.previous()),
                trail,
                difference);
    }

    private static BetaPosterior posterior(List<EvidenceOpportunity> opportunities) {
        double sum = opportunities.stream()
                .mapToDouble(opportunity -> opportunity.outcomes().metShare())
                .sum();
        return BetaPosterior.from(opportunities.size(), sum);
    }

    private static OutcomeVector outcomes(List<EvidenceOpportunity> opportunities) {
        return opportunities.stream()
                .map(EvidenceOpportunity::outcomes)
                .reduce(OutcomeVector.EMPTY, OutcomeVector::plus);
    }
}
