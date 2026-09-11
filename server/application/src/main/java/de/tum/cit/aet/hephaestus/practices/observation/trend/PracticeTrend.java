package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.OutcomeVectorDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeTrendDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendOpportunityDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendSupportDTO;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/** Complete internal trend result; developer-facing DTOs intentionally omit its scalar posterior diagnostics. */
public final class PracticeTrend {

    private final String slug;
    private final TrendScope scope;
    private final TrendDirection direction;
    private final TrendSupport support;
    /** Absent while the evidence is not comparable — the accessors below say the same. */
    private final @Nullable OutcomeVector currentOutcomes;

    private final @Nullable OutcomeVector previousOutcomes;
    private final List<EvidenceOpportunity> opportunities;

    /** Absent for INSUFFICIENT_EVIDENCE, and for a group result, which aggregates rather than diffs. */
    private final BetaPosterior.@Nullable Difference difference;

    PracticeTrend(
            String slug,
            TrendScope scope,
            TrendDirection direction,
            TrendSupport support,
            @Nullable OutcomeVector currentOutcomes,
            @Nullable OutcomeVector previousOutcomes,
            List<EvidenceOpportunity> opportunities,
            BetaPosterior.@Nullable Difference difference) {
        this.slug = slug;
        this.scope = scope;
        this.direction = direction;
        this.support = support;
        this.currentOutcomes = currentOutcomes;
        this.previousOutcomes = previousOutcomes;
        this.opportunities = List.copyOf(opportunities);
        this.difference = difference;
    }

    public String slug() {
        return slug;
    }

    public TrendDirection direction() {
        return direction;
    }

    public TrendSupport support() {
        return support;
    }

    /**
     * The recency-weighted share of positive outcomes across the newest {@code window} evidence opportunities,
     * or empty when none of them produced a verdict.
     *
     * <p>Opportunity-indexed like the trend itself, so a single busy day cannot manufacture a standing and a
     * quiet week cannot erode one — the unit is a reviewed work item, never a calendar bin. Opportunities that
     * carry no applicable outcome are skipped rather than counted as either side, so a review that had no
     * chance to exercise the practice neither helps nor hurts, and it does not push genuine evidence out of
     * the window either.
     *
     * <p>Each opportunity contributes its own {@link OutcomeVector#positiveShare()}, not a clean/dirty bit, so
     * a piece of reviewed work that went half well is counted as half well rather than rounded to a problem.
     *
     * <p>Weights fall geometrically with age ({@code decay^0, decay^1, …} from the newest), which is what lets
     * one rule do the job two used to: recent evidence dominates, so a fixed habit is acknowledged within a
     * couple of reviews without a separate streak override, and a fresh regression is visible just as fast.
     * A {@code decay} strictly below 0.5 is what makes the two newest opportunities outweigh everything older
     * — see the caller that chooses it.
     *
     * @param window how many of the newest applicable opportunities to consider, at least one
     * @param decay per-opportunity weight factor in {@code (0,1]}; 1.0 is an unweighted mean
     */
    public OptionalDouble recentPositiveShare(int window, double decay) {
        List<EvidenceOpportunity> applicable =
                opportunities.stream().filter(EvidenceOpportunity::applicable).toList();
        if (applicable.isEmpty()) {
            return OptionalDouble.empty();
        }
        List<EvidenceOpportunity> recent =
                applicable.subList(Math.max(0, applicable.size() - window), applicable.size());
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (int index = recent.size() - 1, age = 0; index >= 0; index--, age++) {
            double weight = Math.pow(decay, age);
            weighted += weight * recent.get(index).outcomes().positiveShare();
            totalWeight += weight;
        }
        return OptionalDouble.of(weighted / totalWeight);
    }

    TrendScope scope() {
        return scope;
    }

    @Nullable
    OutcomeVector currentOutcomes() {
        return currentOutcomes;
    }

    @Nullable
    OutcomeVector previousOutcomes() {
        return previousOutcomes;
    }

    List<EvidenceOpportunity> opportunities() {
        return opportunities;
    }

    BetaPosterior.@Nullable Difference difference() {
        return difference;
    }

    public PracticeTrendDTO toDto() {
        return new PracticeTrendDTO(
                slug,
                scope,
                direction,
                TrendSupportDTO.from(support),
                OutcomeVectorDTO.from(currentOutcomes),
                OutcomeVectorDTO.from(previousOutcomes),
                java.util.stream.IntStream.range(0, opportunities.size())
                        .mapToObj(index -> {
                            EvidenceOpportunity opportunity = opportunities.get(index);
                            return new TrendOpportunityDTO(
                                    index,
                                    opportunity.occurredAt(),
                                    opportunity.artifactKind(),
                                    opportunity.artifactId(),
                                    new OutcomeVectorDTO(
                                            opportunity.outcomes().demonstratedStrengths(),
                                            opportunity.outcomes().safeAvoidances(),
                                            opportunity.outcomes().commissionProblems(),
                                            opportunity.outcomes().omissionGaps(),
                                            opportunity.outcomes().notApplicable(),
                                            opportunity.outcomes().undetermined()),
                                    opportunity.bundle());
                        })
                        .toList());
    }
}
