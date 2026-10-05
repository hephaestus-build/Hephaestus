package de.tum.cit.aet.hephaestus.practices.observation.trend;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import java.time.Clock;
import java.time.Instant;
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

    /** The start of today's trend horizon, which is the practice profile's look-back. */
    public Instant horizon() {
        return clock.instant().minus(PracticeStandingService.LOOKBACK_DAYS, ChronoUnit.DAYS);
    }

    /** One practice's trend over its evidence observed from {@code since}; insufficient over none. */
    public PracticeTrend calculatePractice(String practiceSlug, List<Observation> evidence, Instant since) {
        return PracticeTrendCalculator.calculatePractice(practiceSlug, evidence, since, properties);
    }

    /** {@link PracticeTrend#recentMetShare} over a practice's requested and backfilled work alone. */
    public OptionalDouble selfSelectedMetShare(List<Observation> evidence, Instant since, int window, double decay) {
        return PracticeTrend.recentMetShare(
                OpportunityBundler.opportunities(evidence, since, true).reversed(), window, decay);
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
