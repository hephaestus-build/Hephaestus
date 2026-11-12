package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackSourceCountDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService.StandingSnapshot.PracticeStandingSnapshot;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingResultDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.TrendSupportDTO;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.result.trend.TrendSupport;
import de.tum.cit.aet.hephaestus.practices.spi.GuidanceSource;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
import de.tum.cit.aet.hephaestus.practices.spi.TrendDirection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The weekly digest that Heph reads compares standings. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeGroupStandingService {

    private static final int MAX_GROUP_EVIDENCE_ITEMS = 5;

    private final PracticeStandingService practiceStandingService;
    private final PracticeTrendService practiceTrendService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PracticeGroupStandingDTO> getGroupStandings(Long workspaceId, List<PracticeGroup> groups) {
        return summarize(groups, practiceStandingService.getStandingSnapshot(workspaceId));
    }

    public List<PracticeGroupStandingDTO> summarize(
            List<PracticeGroup> groups, PracticeStandingService.StandingSnapshot snapshot) {
        Map<String, List<PracticeStandingDTO>> cardsByGroup = cardsByGroup(snapshot.dtos());
        Map<String, GroupStandingInput> inputsByGroup = groupInputs(snapshot);
        return groups.stream()
                .map(group -> toGroupStanding(
                        group,
                        cardsByGroup.getOrDefault(group.getSlug(), List.of()),
                        snapshot.practices(),
                        Set.copyOf(snapshot.eligiblePracticesByGroup().getOrDefault(group.getSlug(), List.of())),
                        inputsByGroup.getOrDefault(group.getSlug(), GroupStandingInput.NONE)))
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
            Map<String, PracticeStandingSnapshot> practices,
            Set<String> eligiblePracticeSlugs,
            GroupStandingInput input) {
        List<PracticeStandingDTO> verdicts = votingVerdicts(cards, eligiblePracticeSlugs);
        PracticeStanding standing = groupStanding(cards, verdicts, practices);
        boolean hasDisplayableData = StandingScale.isVerdict(standing);
        // Item-level, unlike the standing: the question here is which KINDS of evidence exist to show, which a
        // practice standing has already abstracted away.
        boolean hasProblems = cards.stream().anyMatch(card -> !card.toWorkOn().isEmpty());
        boolean hasStrengths = cards.stream().anyMatch(card -> !card.strengths().isEmpty());

        String guidance = hasDisplayableData ? DeterministicGroupGuidanceComposer.compose(standing, verdicts) : null;
        GuidanceSource guidanceSource = hasDisplayableData ? GuidanceSource.RULE_BASED : null;

        return new PracticeGroupStandingDTO(
                group.getSlug(),
                group.getName(),
                group.getDescription(),
                group.getIcon(),
                group.getColor(),
                standing,
                guidance,
                guidanceSource,
                input.direction(),
                input.trendSupport(),
                hasDisplayableData ? input.feedbackSpanDays() : null,
                hasDisplayableData ? input.feedbackSince() : null,
                groupEvidence(cards, hasProblems, hasStrengths),
                hasDisplayableData ? input.sources() : List.of(),
                hasDisplayableData ? basis(verdicts) : null);
    }

    // A group standing averages its practices' verdicts, so it carries the weakest basis among them.
    private static StandingBasis basis(List<PracticeStandingDTO> verdicts) {
        return verdicts.stream()
                .map(PracticeStandingDTO::basis)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(StandingBasis.CURRENT_REVIEWS);
    }

    private static List<PracticeStandingDTO> votingVerdicts(
            List<PracticeStandingDTO> cards, Set<String> eligiblePracticeSlugs) {
        return cards.stream()
                .filter(card -> StandingScale.isVerdict(card.standing()))
                .filter(card -> eligiblePracticeSlugs.contains(card.slug()))
                .toList();
    }

    private static PracticeStanding groupStanding(
            List<PracticeStandingDTO> cards,
            List<PracticeStandingDTO> verdicts,
            Map<String, PracticeStandingSnapshot> practices) {
        if (verdicts.isEmpty()) {
            return cards.stream().anyMatch(card -> card.standing() == PracticeStanding.NO_OPPORTUNITY)
                    ? PracticeStanding.NO_OPPORTUNITY
                    : PracticeStanding.NOT_OBSERVED;
        }
        double groupShare = verdicts.stream()
                .mapToDouble(card -> Objects.requireNonNull(
                        Objects.requireNonNull(practices.get(card.slug())).share()))
                .average()
                .orElseThrow();
        return StandingScale.classify(groupShare);
    }

    private static List<PracticeStandingResultDTO> groupEvidence(
            List<PracticeStandingDTO> cards, boolean hasProblems, boolean hasStrengths) {
        List<PracticeStandingResultDTO> problems =
                cards.stream().flatMap(card -> card.toWorkOn().stream()).toList();
        List<PracticeStandingResultDTO> strengths =
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

    private record GroupStandingInput(
            @Nullable TrendDirection direction,
            @Nullable TrendSupportDTO trendSupport,
            @Nullable Integer feedbackSpanDays,
            @Nullable Instant feedbackSince,
            List<FeedbackSourceCountDTO> sources) {
        private static final GroupStandingInput NONE = new GroupStandingInput(null, null, null, null, List.of());
    }

    private Map<String, GroupStandingInput> groupInputs(PracticeStandingService.StandingSnapshot snapshot) {
        Map<String, List<ResultView>> evidenceByGroup = new LinkedHashMap<>();
        Map<String, List<PracticeTrend>> trendsByGroup = new LinkedHashMap<>();
        Set<String> eligibleSlugs = snapshot.eligiblePracticesByGroup().values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        for (PracticeStandingSnapshot practice : snapshot.practices().values()) {
            String groupSlug = practice.dto().groupSlug();
            if (groupSlug == null) {
                continue;
            }
            if (!practice.evidence().isEmpty()) {
                evidenceByGroup
                        .computeIfAbsent(groupSlug, ignored -> new ArrayList<>())
                        .addAll(practice.evidence());
            }
            if (!practice.evidence().isEmpty()
                    && eligibleSlugs.contains(practice.dto().slug())) {
                trendsByGroup
                        .computeIfAbsent(groupSlug, ignored -> new ArrayList<>())
                        .add(practice.trend());
            }
        }

        Instant now = clock.instant();
        Map<String, GroupStandingInput> inputs = new HashMap<>();
        Set<String> groupSlugs = new LinkedHashSet<>(evidenceByGroup.keySet());
        groupSlugs.addAll(snapshot.eligiblePracticesByGroup().keySet());
        for (String groupSlug : groupSlugs) {
            List<ResultView> evidence = evidenceByGroup.getOrDefault(groupSlug, List.of());
            Instant oldest = evidence.stream()
                    .map(view -> view.result().getRecordedAt())
                    .min(Instant::compareTo)
                    .orElse(null);
            PracticeTrend direction = practiceTrendService.calculateGroup(
                    snapshot.eligiblePracticesByGroup().getOrDefault(groupSlug, List.of()),
                    trendsByGroup.getOrDefault(groupSlug, List.of()));
            inputs.put(
                    groupSlug,
                    new GroupStandingInput(
                            direction.direction(),
                            TrendSupportDTO.from(direction.support()),
                            oldest == null
                                    ? null
                                    : Math.min(
                                            PracticeTrendService.LOOKBACK_DAYS,
                                            TrendSupport.inclusiveUtcDaySpan(oldest, now)),
                            oldest,
                            sourceCounts(evidence)));
        }
        return inputs;
    }

    private static List<FeedbackSourceCountDTO> sourceCounts(List<ResultView> evidence) {
        // Ordered by kind value, not by declaration order: ItemKind is an open vocabulary of
        // "<domain>.<kind>" strings, so there is no enum ordinal left to render stably against.
        Map<ItemKind, Set<Long>> artifactsByType = new TreeMap<>(Comparator.comparing(ItemKind::value));
        for (ResultView view : evidence) {
            artifactsByType
                    .computeIfAbsent(new ItemKind(view.item().kind()), ignored -> new HashSet<>())
                    .add(view.result().getItemId());
        }
        return artifactsByType.entrySet().stream()
                .map(entry -> new FeedbackSourceCountDTO(
                        entry.getKey().value(), entry.getValue().size()))
                .toList();
    }
}
