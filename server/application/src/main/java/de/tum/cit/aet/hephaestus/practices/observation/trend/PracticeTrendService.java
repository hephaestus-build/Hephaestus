package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PracticeTrendService {

    private final TrendProperties properties;
    private final Clock clock;

    /** One practice's trend over its evidence inside today's horizon; insufficient over none. */
    public PracticeTrend calculatePractice(String practiceSlug, List<Observation> evidence) {
        var cutoff = clock.instant().minus(properties.getHorizonDays(), ChronoUnit.DAYS);
        return PracticeTrendCalculator.calculatePractice(practiceSlug, evidence, cutoff, properties);
    }

    /**
     * {@link PracticeTrend#recentMetShare} over a practice's backfilled work alone, inside today's horizon. A
     * campaign's reading is sound on its own and never part of a live trend, so this is what a standing reads
     * for a practice that only a campaign judged.
     */
    public OptionalDouble backfilledMetShare(List<Observation> evidence, int window, double decay) {
        var cutoff = clock.instant().minus(properties.getHorizonDays(), ChronoUnit.DAYS);
        return PracticeTrend.recentMetShare(
                OpportunityBundler.opportunities(evidence, cutoff, true).reversed(), window, decay);
    }

    public PracticeTrend calculateGroup(
            String groupSlug, Collection<String> eligiblePracticeSlugs, Collection<PracticeTrend> practiceTrends) {
        return GroupTrendAggregator.aggregate(groupSlug, eligiblePracticeSlugs, practiceTrends, properties);
    }

    /** The group's trend and the trends it aggregates: one per eligible practice, in the practices' order. */
    public PracticeGroupTrendDTO detail(
            String groupSlug, Collection<String> eligiblePracticeSlugs, List<PracticeTrend> practiceTrends) {
        PracticeTrend group = calculateGroup(groupSlug, eligiblePracticeSlugs, practiceTrends);
        return new PracticeGroupTrendDTO(
                group.toDto(), practiceTrends.stream().map(PracticeTrend::toDto).toList());
    }
}
