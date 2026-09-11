package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingObservationDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.observation.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendSupportDTO;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeStandingService {

    public static final int LOOKBACK_DAYS = 90;
    private static final int MAX_FEEDBACK_PER_PRACTICE = 5;
    private static final int STANDING_WINDOW = 4;
    /**
     * Per-opportunity weight decay, newest first. Derived rather than picked: two problem-free pieces of reviewed work in a
     * row must be enough to acknowledge a fixed habit, which with weights {@code 1, d, d², d³} holds exactly
     * when {@code (1 + d) > 4·(d² + d³)}, i.e. {@code d < 0.5}. The effect is symmetric and intended: a fresh
     * regression shows up as fast as a fresh fix.
     */
    private static final double STANDING_DECAY = 0.4;

    private static final int MAX_STRENGTHS_PER_PRACTICE = 3;

    private final ObservationRepository observationRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final PracticeRepository practiceRepository;
    private final WorkspaceReviewDefaultsProvider workspaceReviewDefaultsProvider;
    private final PracticeTrendService practiceTrendService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PracticeStandingDTO> getStandings(Long workspaceId) {
        return getStandingSnapshot(workspaceId).practices();
    }

    public StandingSnapshot getStandingSnapshot(Long workspaceId) {
        Optional<Long> currentDeveloperId = currentDeveloperLookup.currentDeveloperId();
        if (currentDeveloperId.isEmpty()) {
            return StandingSnapshot.EMPTY;
        }
        Instant since = clock.instant().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        Long developerId = currentDeveloperId.get();
        List<Observation> recent = observationRepository.findRecentByDeveloperAndWorkspace(
                developerId,
                workspaceId,
                since,
                // Verdictless observations distinguish NO_OPPORTUNITY from NOT_OBSERVED.
                false,
                Pageable.unpaged());
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, recent, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<Observation> observations = recent.stream()
                .filter(observation -> visible.contains(observation.getId()))
                .toList();
        Map<UUID, String> deliveredGuidance = deliveredGuidanceByObservation(
                workspaceId, observations.stream().map(Observation::getId).collect(Collectors.toSet()));

        Map<String, List<Observation>> byPractice = new LinkedHashMap<>();
        for (Observation observation : observations) {
            byPractice
                    .computeIfAbsent(observation.getPractice().getSlug(), ignored -> new ArrayList<>())
                    .add(observation);
        }

        Map<String, PracticeEvidence> evidenceBySlug = new LinkedHashMap<>();
        for (List<Observation> group : byPractice.values()) {
            PracticeEvidence evidence = PracticeEvidence.classify(group);
            evidenceBySlug.put(evidence.slug(), evidence);
        }
        Map<String, List<Observation>> evidenceByPractice = evidenceBySlug.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().observed(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));

        Map<String, PracticeTrend> trends = practiceTrendService.calculatePractices(evidenceByPractice);
        Map<String, Double> standingShareByPractice = evidenceBySlug.values().stream()
                .filter(PracticeEvidence::hasStanding)
                .collect(Collectors.toMap(
                        PracticeEvidence::slug,
                        evidence -> standingShare(evidence, requireTrend(trends, evidence.slug())),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
        PracticeAutonomy workspaceDefault =
                workspaceReviewDefaultsProvider.forWorkspace(workspaceId).defaultAutonomy();
        List<Practice> eligiblePractices = practiceRepository.findByWorkspaceId(workspaceId).stream()
                .filter(practice -> AutonomyResolver.effectiveAutonomyOf(practice, workspaceDefault)
                        .admitsReview())
                .toList();
        Map<String, List<String>> eligiblePracticesByGroup = new LinkedHashMap<>();
        for (Practice practice : eligiblePractices) {
            PracticeGroup group = practice.getGroup();
            if (group != null) {
                eligiblePracticesByGroup
                        .computeIfAbsent(group.getSlug(), slug -> new ArrayList<>())
                        .add(practice.getSlug());
            }
        }
        List<PracticeStandingDTO> practices =
                practices(evidenceBySlug, eligiblePractices, trends, standingShareByPractice, deliveredGuidance);

        return new StandingSnapshot(
                developerId, practices, evidenceByPractice, eligiblePracticesByGroup, standingShareByPractice);
    }

    /**
     * Every practice the developer should see, whether or not it has anything to say.
     *
     * <p>The UNION of two sets, both needed. The eligible practices are what the workspace currently watches;
     * they belong here even with nothing to report, because "no observation reached this" and "the reviews ran
     * and found nothing" are different answers a developer cannot otherwise tell apart.
     * Practices with a standing are included even when review is no longer admitted for them: that
     * feedback was raised and delivered, and switching a practice off does not un-say it.
     */
    private static List<PracticeStandingDTO> practices(
            Map<String, PracticeEvidence> evidenceBySlug,
            List<Practice> eligiblePractices,
            Map<String, PracticeTrend> trends,
            Map<String, Double> standingShareByPractice,
            Map<UUID, String> deliveredGuidance) {
        Map<String, Practice> subjects = new LinkedHashMap<>();
        eligiblePractices.forEach(practice -> subjects.put(practice.getSlug(), practice));
        evidenceBySlug.values().forEach(evidence -> subjects.putIfAbsent(evidence.slug(), evidence.practice()));

        return subjects.entrySet().stream()
                .map(entry -> {
                    PracticeEvidence evidence = evidenceBySlug.get(entry.getKey());
                    Double share = standingShareByPractice.get(entry.getKey());
                    return evidence != null && share != null
                            ? toStanding(evidence, requireTrend(trends, entry.getKey()), deliveredGuidance, share)
                            : silentStanding(entry.getValue(), evidence);
                })
                .sorted(Comparator.<PracticeStandingDTO>comparingInt(
                                practiceStanding -> standingRank(practiceStanding.standing()))
                        .thenComparingInt(PracticeStandingService::worstSeverityOrdinal))
                .toList();
    }

    /**
     * A practice with nothing to report, carrying WHICH silence it is.
     *
     * <p>{@code NO_OPPORTUNITY} outranks {@code NOT_OBSERVED}: a review that ran and found nothing to say is a
     * working instrument, not an unconfigured one. Suppressed strengths count for the same reason. A
     * defect-detector's silence is no demonstrated behaviour, but it does prove the detector ran.
     *
     * <p>No trend either: a direction over evidence that produced no verdict would be a claim about nothing.
     */
    private static PracticeStandingDTO silentStanding(Practice practice, @Nullable PracticeEvidence evidence) {
        boolean exercised =
                evidence != null && (!evidence.withoutVerdict().isEmpty() || evidence.suppressedStrengths() > 0);
        PracticeGroup group = practice.getGroup();
        return new PracticeStandingDTO(
                practice.getSlug(),
                practice.getName(),
                group != null ? group.getSlug() : null,
                group != null ? group.getName() : null,
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                exercised ? PracticeStandingDTO.Standing.NO_OPPORTUNITY : PracticeStandingDTO.Standing.NOT_OBSERVED,
                List.of(),
                List.of(),
                null,
                null);
    }

    /**
     * One practice response, complete on first construction.
     *
     * <p>The trend is required, not optional: every practice that has a standing has an entry in the evidence map
     * the trends were derived from, so a missing trend is a programming error rather than a state to render
     * around.
     */
    private static PracticeStandingDTO toStanding(
            PracticeEvidence evidence, PracticeTrend trend, Map<UUID, String> deliveredGuidance, double standingShare) {
        Practice practice = evidence.practice();
        PracticeGroup group = practice.getGroup();
        return new PracticeStandingDTO(
                practice.getSlug(),
                practice.getName(),
                group != null ? group.getSlug() : null,
                group != null ? group.getName() : null,
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                StandingScale.classify(standingShare),
                feedback(evidence.problems(), MAX_FEEDBACK_PER_PRACTICE, deliveredGuidance),
                feedback(evidence.strengths(), MAX_STRENGTHS_PER_PRACTICE, deliveredGuidance),
                trend.direction(),
                TrendSupportDTO.from(trend.support()));
    }

    /**
     * The trend of a practice that has a standing.
     *
     * <p>Never absent: the trends were derived from exactly the evidence map these practices came from,
     * so a missing entry is a programming error rather than a state to render around.
     */
    private static PracticeTrend requireTrend(Map<String, PracticeTrend> trends, String slug) {
        return Objects.requireNonNull(trends.get(slug), () -> "no trend for practice with a standing: " + slug);
    }

    private static List<PracticeStandingObservationDTO> feedback(
            List<Observation> observations, int cap, Map<UUID, String> deliveredGuidance) {
        return observations.stream()
                .limit(cap)
                .map(observation ->
                        PracticeStandingObservationDTO.from(observation, deliveredGuidance.get(observation.getId())))
                .toList();
    }

    /**
     * How positive this practice's recent evidence was, in {@code [0,1]}. The standing label is a rendering of
     * this number, and the level above consumes the number rather than the label.
     *
     * <p>One rule over the newest {@link #STANDING_WINDOW} opportunities, weighted by recency. It replaced a
     * pair that disagreed about both unit and denominator: an existence test over observations that could not tell
     * one problem from fifty, plus a clean-streak override over opportunities that could.
     *
     * <p>The fallback is unreachable while the look-back and the trend horizon are both
     * {@link #LOOKBACK_DAYS} days, since a standing exists only where some observation produced a verdict.
     */
    private static double standingShare(PracticeEvidence evidence, PracticeTrend trend) {
        return trend.recentPositiveShare(STANDING_WINDOW, STANDING_DECAY)
                .orElseGet(() -> evidence.problems().isEmpty() ? 1.0 : 0.0);
    }

    /**
     * One practice's window of observations, split by what each one says about the developer.
     *
     * <p>Split once, then read by everything downstream: the practice's two lists, the trend's evidence, the
     * census. Deriving each separately from the raw group is what previously required three output parameters
     * and a provisional standing.
     */
    private record PracticeEvidence(
            Practice practice,
            List<Observation> problems,
            List<Observation> strengths,
            int suppressedStrengths,
            List<Observation> withoutVerdict) {
        /**
         * Sorts one practice's window into the buckets every downstream reader needs, reading
         * {@link ObservationKind} exactly once per observation.
         *
         * <p>Grouping on the outcome rather than testing it per bucket is what makes the split visibly
         * exhaustive: the five outcomes are the five keys, and every row lands under one of them. The earlier
         * shape asked {@code isNegative} / {@code isPositive} / {@code isCoherentStrengthFor} in sequence, which
         * evaluated the matrix two to four times per row and left "nothing falls through" as a claim in prose
         * rather than something the code shows.
         *
         * <p>Every problem is kept, worst severity first. Nothing is withheld: an earlier confidence floor was
         * dropped after validation found it carried no signal, and the per-locus corroboration meant to replace
         * it cannot work, since {@code recurrenceKey} hashes the artifact and a locus is therefore
         * single-artifact by construction.
         *
         * <p>A defect-detector's {@code DEMONSTRATED_STRENGTH} rows are counted, not discarded. What would be
         * demonstrated is the defect, so they are no strength to show, but they do prove the detector ran. The
         * rule is applied once to the bucket rather than once per row.
         */
        static PracticeEvidence classify(List<Observation> group) {
            Practice practice = group.get(0).getPractice();
            Map<ObservationKind, List<Observation>> byOutcome =
                    group.stream().collect(Collectors.groupingBy(ObservationKind::of));
            List<Observation> demonstrated = bucket(byOutcome, ObservationKind.DEMONSTRATED_STRENGTH);
            List<Observation> avoided = bucket(byOutcome, ObservationKind.SAFE_AVOIDANCE);
            boolean detectorStrengthIsIncoherent =
                    !ObservationKind.DEMONSTRATED_STRENGTH.isCoherentStrengthFor(practice.isDefectDetector());
            return new PracticeEvidence(
                    practice,
                    Stream.concat(
                                    bucket(byOutcome, ObservationKind.COMMISSION_PROBLEM).stream(),
                                    bucket(byOutcome, ObservationKind.OMISSION_GAP).stream())
                            .sorted(Comparator.comparingInt(PracticeStandingService::severityOrdinal))
                            .toList(),
                    detectorStrengthIsIncoherent
                            ? avoided
                            : Stream.concat(demonstrated.stream(), avoided.stream())
                                    .toList(),
                    detectorStrengthIsIncoherent ? demonstrated.size() : 0,
                    Stream.concat(
                                    bucket(byOutcome, ObservationKind.NOT_APPLICABLE).stream(),
                                    bucket(byOutcome, ObservationKind.UNDETERMINED).stream())
                            .toList());
        }

        private static List<Observation> bucket(
                Map<ObservationKind, List<Observation>> byOutcome, ObservationKind outcome) {
            return byOutcome.getOrDefault(outcome, List.of());
        }

        String slug() {
            return practice.getSlug();
        }

        /**
         * Everything the practice's latest runs said, verdict or not. This is the trend's input.
         *
         * <p>The verdictless rows belong here even though they can never move a direction. The bundler drops an
         * opportunity that produced no verdict at all, so including them changes no share and no posterior; what
         * it does change is that a piece of reviewed work that the practice looked at and could not judge is visible as an
         * opportunity that yielded nothing, rather than as an absence indistinguishable from work that was never
         * reviewed. That is the same distinction {@code NO_OPPORTUNITY} draws one level up.
         */
        List<Observation> observed() {
            return Stream.concat(Stream.concat(problems.stream(), strengths.stream()), withoutVerdict.stream())
                    .toList();
        }

        /** Whether this practice has anything to say to the developer. */
        boolean hasStanding() {
            return !problems.isEmpty() || !strengths.isEmpty();
        }
    }

    /**
     * @param standingShareByPractice the continuous standing of every practice that has a standing, keyed by
     *     slug. The level above aggregates THIS rather than the rendered labels, which would put 0.79 and 0.51
     *     at the same weight. Kept out of {@link PracticeStandingDTO}: the developer-facing response carries no score.
     */
    public record StandingSnapshot(
            @Nullable Long developerId,
            List<PracticeStandingDTO> practices,
            Map<String, List<Observation>> evidenceByPractice,
            Map<String, List<String>> eligiblePracticesByGroup,
            Map<String, Double> standingShareByPractice) {
        static final StandingSnapshot EMPTY = new StandingSnapshot(null, List.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * The lanes whose text this read model's guidance means: the ones that speak about the one observation
     * they are bound to. {@code IN_APP} is excluded because it is feedback about a habit across several
     * pieces of work, so it would answer "what did you tell me about this observation" with a paragraph that
     * is explicitly not about it.
     */
    private static final List<String> FEEDBACK_CHANNELS =
            List.of(FeedbackChannel.IN_CONTEXT.name(), FeedbackChannel.IN_CHAT.name());

    private Map<UUID, String> deliveredGuidanceByObservation(Long workspaceId, Set<UUID> observationIds) {
        if (observationIds.isEmpty()) {
            return Map.of();
        }
        return feedbackObservationRepository
                .findLatestFeedbackBodiesByObservationIds(workspaceId, observationIds, FEEDBACK_CHANNELS)
                .stream()
                .collect(Collectors.toMap(ObservationFeedbackBody::getObservationId, ObservationFeedbackBody::getBody));
    }

    /** Verdicts first and worst first; silences last. */
    private static int standingRank(PracticeStandingDTO.Standing standing) {
        return switch (standing) {
            case DEVELOPING -> 0;
            case MIXED -> 1;
            case STRENGTH -> 2;
            case NO_OPPORTUNITY -> 3;
            case NOT_OBSERVED -> 4;
        };
    }

    private static int worstSeverityOrdinal(PracticeStandingDTO practiceStanding) {
        return practiceStanding.toWorkOn().stream()
                .mapToInt(observation -> observation.severity() == null
                        ? Severity.values().length
                        : observation.severity().ordinal())
                .min()
                .orElse(Severity.values().length);
    }

    private static int severityOrdinal(Observation observation) {
        return observation.getSeverity() == null
                ? Severity.values().length
                : observation.getSeverity().ordinal();
    }
}
