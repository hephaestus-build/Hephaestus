package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackSourceCountDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingObservationDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendSupportDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeGroupStandingService {

    /** Evidence cap: enough to make a standing inspectable, not an exhaustive log. */
    private static final int MAX_GROUP_EVIDENCE_ITEMS = 5;

    private final PracticeStandingService practiceStandingService;
    private final PracticeTrendService practiceTrendService;
    private final Clock clock;

    /**
     * One standing per requested group, rolled up from the practice standings of the same developer snapshot
     * the per-practice surface renders. Observations are loaded once and partitioned in memory rather than
     * queried per practice.
     *
     * <p>With nothing displayable the standing reports why, as {@code NO_OPPORTUNITY} or {@code NOT_OBSERVED},
     * instead of one undifferentiated empty state.
     */
    @Transactional(readOnly = true)
    public List<PracticeGroupStandingDTO> getGroupStandings(Long workspaceId, List<PracticeGroup> groups) {
        PracticeStandingService.StandingSnapshot snapshot = practiceStandingService.getStandingSnapshot(workspaceId);
        Map<String, List<PracticeStandingDTO>> cardsByGroup = cardsByGroup(snapshot.practices());
        Map<String, PracticeTrend> practiceTrends =
                practiceTrendService.calculatePractices(snapshot.evidenceByPractice());
        Map<String, GroupSignal> signalsByGroup =
                groupSignals(snapshot.evidenceByPractice(), practiceTrends, snapshot.eligiblePracticesByGroup());
        return groups.stream()
                .map(group -> toGroupStanding(
                        group,
                        cardsByGroup.getOrDefault(group.getSlug(), List.of()),
                        snapshot.standingShareByPractice(),
                        Set.copyOf(snapshot.eligiblePracticesByGroup().getOrDefault(group.getSlug(), List.of())),
                        signalsByGroup.getOrDefault(group.getSlug(), GroupSignal.NONE)))
                .toList();
    }

    private static Map<String, List<PracticeStandingDTO>> cardsByGroup(List<PracticeStandingDTO> cards) {
        Map<String, List<PracticeStandingDTO>> cardsByGroup = new LinkedHashMap<>();
        for (PracticeStandingDTO card : cards) {
            if (card.groupSlug() != null) {
                cardsByGroup
                        .computeIfAbsent(card.groupSlug(), ignored -> new ArrayList<>())
                        .add(card);
            }
        }
        return cardsByGroup;
    }

    private static PracticeGroupStandingDTO toGroupStanding(
            PracticeGroup group,
            List<PracticeStandingDTO> cards,
            Map<String, Double> standingShareByPractice,
            Set<String> eligiblePracticeSlugs,
            GroupSignal signal) {
        List<PracticeStandingDTO> verdicts = votingVerdicts(cards, eligiblePracticeSlugs);
        PracticeGroupStandingDTO.Standing standing = groupStanding(cards, verdicts, standingShareByPractice);
        boolean hasDisplayableData = PracticeGroupStandingDTO.isVerdict(standing);
        // Item-level, unlike the standing: the question here is which KINDS of evidence exist to show, which a
        // practice standing has already abstracted away.
        boolean hasProblems = cards.stream().anyMatch(card -> !card.toWorkOn().isEmpty());
        boolean hasStrengths = cards.stream().anyMatch(card -> !card.strengths().isEmpty());

        String guidance = hasDisplayableData ? DeterministicGroupGuidanceComposer.compose(standing, verdicts) : null;
        PracticeGroupStandingDTO.GuidanceSource guidanceSource =
                hasDisplayableData ? PracticeGroupStandingDTO.GuidanceSource.RULE_BASED : null;

        return new PracticeGroupStandingDTO(
                group.getSlug(),
                group.getName(),
                standing,
                guidance,
                guidanceSource,
                signal.direction(),
                signal.trendSupport(),
                hasDisplayableData ? signal.feedbackSpanDays() : null,
                hasDisplayableData ? signal.feedbackSince() : null,
                groupEvidence(cards, hasProblems, hasStrengths),
                hasDisplayableData ? signal.sources() : List.of());
    }

    private static List<PracticeStandingDTO> votingVerdicts(
            List<PracticeStandingDTO> cards, Set<String> eligiblePracticeSlugs) {
        return cards.stream()
                .filter(card -> PracticeStandingDTO.isVerdict(card.standing()))
                .filter(card -> eligiblePracticeSlugs.contains(card.slug()))
                .toList();
    }

    private static PracticeGroupStandingDTO.Standing groupStanding(
            List<PracticeStandingDTO> cards,
            List<PracticeStandingDTO> verdicts,
            Map<String, Double> standingShareByPractice) {
        if (verdicts.isEmpty()) {
            return cards.stream().anyMatch(card -> card.standing() == PracticeStandingDTO.Standing.NO_OPPORTUNITY)
                    ? PracticeGroupStandingDTO.Standing.NO_OPPORTUNITY
                    : PracticeGroupStandingDTO.Standing.NOT_OBSERVED;
        }
        double groupShare = verdicts.stream()
                .mapToDouble(card -> standingShareByPractice.getOrDefault(card.slug(), 0.0))
                .average()
                .orElseThrow();
        return switch (StandingScale.classify(groupShare)) {
            case STRENGTH -> PracticeGroupStandingDTO.Standing.STRENGTH;
            case MIXED -> PracticeGroupStandingDTO.Standing.MIXED;
            case DEVELOPING -> PracticeGroupStandingDTO.Standing.DEVELOPING;
            case NOT_OBSERVED, NO_OPPORTUNITY ->
                throw new IllegalStateException("StandingScale only classifies verdicts");
        };
    }

    /**
     * Problems lead because they explain the action-oriented standing. A mixed standing always reserves one
     * of the five evidence slots for a strength, so the payload cannot say MIXED while showing only one side.
     */
    private static List<PracticeStandingObservationDTO> groupEvidence(
            List<PracticeStandingDTO> cards, boolean hasProblems, boolean hasStrengths) {
        List<PracticeStandingObservationDTO> problems =
                cards.stream().flatMap(card -> card.toWorkOn().stream()).toList();
        List<PracticeStandingObservationDTO> strengths =
                cards.stream().flatMap(card -> card.strengths().stream()).toList();
        if (hasProblems && hasStrengths && problems.size() >= MAX_GROUP_EVIDENCE_ITEMS) {
            return Stream.concat(
                            problems.stream().limit(MAX_GROUP_EVIDENCE_ITEMS - 1L),
                            strengths.stream().limit(1))
                    .toList();
        }
        return Stream.concat(problems.stream(), strengths.stream())
                .limit(MAX_GROUP_EVIDENCE_ITEMS)
                .toList();
    }

    /** Group-level direction and provenance, derived from the same visible evidence as its standing. */
    private record GroupSignal(
            @Nullable TrendDirection direction,
            @Nullable TrendSupportDTO trendSupport,
            @Nullable Integer feedbackSpanDays,
            @Nullable Instant feedbackSince,
            List<FeedbackSourceCountDTO> sources) {
        private static final GroupSignal NONE = new GroupSignal(null, null, null, null, List.of());
    }

    private Map<String, GroupSignal> groupSignals(
            Map<String, List<Observation>> evidenceByPractice,
            Map<String, PracticeTrend> practiceTrends,
            Map<String, List<String>> eligiblePracticesByGroup) {
        Map<String, List<Observation>> evidenceByGroup = new LinkedHashMap<>();
        Map<String, List<PracticeTrend>> trendsByGroup = new LinkedHashMap<>();
        Set<String> eligibleSlugs =
                eligiblePracticesByGroup.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        for (Map.Entry<String, List<Observation>> entry : evidenceByPractice.entrySet()) {
            List<Observation> practiceEvidence = entry.getValue();
            if (practiceEvidence.isEmpty()) {
                continue;
            }
            PracticeGroup group = practiceEvidence.get(0).getPractice().getGroup();
            if (group == null) {
                continue;
            }
            evidenceByGroup
                    .computeIfAbsent(group.getSlug(), ignored -> new ArrayList<>())
                    .addAll(practiceEvidence);
            PracticeTrend practiceTrend = practiceTrends.get(entry.getKey());
            // Only an eligible practice's trend joins the group's. The evidence map stays unfiltered on
            // purpose: a practice review is no longer admitted for still has observations worth showing, and the
            // feedback span is still dated by them. Its VERDICT is what it has stopped casting, and the
            // standing beside this already excludes it.
            if (practiceTrend != null && eligibleSlugs.contains(entry.getKey())) {
                trendsByGroup
                        .computeIfAbsent(group.getSlug(), ignored -> new ArrayList<>())
                        .add(practiceTrend);
            }
        }

        Instant now = clock.instant();
        Map<String, GroupSignal> signals = new HashMap<>();
        for (Map.Entry<String, List<Observation>> entry : evidenceByGroup.entrySet()) {
            List<Observation> evidence = entry.getValue();
            Instant oldest = evidence.stream()
                    .map(Observation::getObservedAt)
                    .min(Instant::compareTo)
                    .orElse(null);
            Integer spanDays = oldest == null ? null : inclusiveUtcDaySpan(oldest, now);
            PracticeTrend direction = practiceTrendService.calculateGroup(
                    entry.getKey(),
                    eligiblePracticesByGroup.getOrDefault(entry.getKey(), List.of()),
                    trendsByGroup.getOrDefault(entry.getKey(), List.of()));
            signals.put(
                    entry.getKey(),
                    new GroupSignal(
                            direction.direction(),
                            TrendSupportDTO.from(direction.support()),
                            spanDays,
                            oldest,
                            sourceCounts(evidence)));
        }
        for (Map.Entry<String, List<String>> entry : eligiblePracticesByGroup.entrySet()) {
            PracticeTrend direction = practiceTrendService.calculateGroup(
                    entry.getKey(), entry.getValue(), trendsByGroup.getOrDefault(entry.getKey(), List.of()));
            signals.putIfAbsent(
                    entry.getKey(),
                    new GroupSignal(
                            direction.direction(), TrendSupportDTO.from(direction.support()), null, null, List.of()));
        }
        return signals;
    }

    private static int inclusiveUtcDaySpan(Instant oldest, Instant newest) {
        long calendarDays = ChronoUnit.DAYS.between(
                        oldest.atZone(ZoneOffset.UTC).toLocalDate(),
                        newest.atZone(ZoneOffset.UTC).toLocalDate())
                + 1;
        return (int) Math.max(1, Math.min(PracticeStandingService.LOOKBACK_DAYS, calendarDays));
    }

    private static List<FeedbackSourceCountDTO> sourceCounts(List<Observation> evidence) {
        // Ordered by kind value, not by declaration order: ArtifactKind is an open vocabulary of
        // "<domain>.<kind>" strings, so there is no enum ordinal left to render stably against.
        Map<ArtifactKind, Set<Long>> artifactsByType = new TreeMap<>(Comparator.comparing(ArtifactKind::value));
        for (Observation observation : evidence) {
            artifactsByType
                    .computeIfAbsent(observation.getArtifactKind(), ignored -> new HashSet<>())
                    .add(observation.getArtifactId());
        }
        return artifactsByType.entrySet().stream()
                .map(entry -> new FeedbackSourceCountDTO(
                        entry.getKey(), (long) entry.getValue().size()))
                .toList();
    }
}
