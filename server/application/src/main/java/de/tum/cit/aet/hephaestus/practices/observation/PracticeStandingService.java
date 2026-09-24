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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
        return getStandingSnapshot(workspaceId).dtos();
    }

    /** The current developer's standings as they stand now; empty for a caller who is not a synced developer. */
    public StandingSnapshot getStandingSnapshot(Long workspaceId) {
        return currentDeveloperLookup
                .currentDeveloperId()
                .map(developerId -> getStandingSnapshot(workspaceId, developerId))
                .orElse(StandingSnapshot.EMPTY);
    }

    /** One developer's standings as they stand now. */
    public StandingSnapshot getStandingSnapshot(Long workspaceId, Long developerId) {
        return getStandingSnapshots(developerId, workspaceId, List.of(clock.instant()))
                .getFirst();
    }

    /**
     * The developer's standings as they stood at each of {@code edges}, in that order, from one load of the
     * look-back up to the newest edge.
     *
     * <p>An edge bounds only the evidence: the look-back start, the eligible practices, the guidance and the
     * trend horizon are today's, so two snapshots of one developer differ in exactly the observations recorded
     * between the two moments. That is the property the practice profile's "what changed" reads off them.
     */
    public List<StandingSnapshot> getStandingSnapshots(Long developerId, Long workspaceId, List<Instant> edges) {
        Instant since = clock.instant().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        Instant until = Collections.max(edges);
        // Verdictless observations distinguish NO_OPPORTUNITY from NOT_OBSERVED.
        List<Observation> window =
                observationRepository.findByDeveloperAndWorkspaceBetween(developerId, workspaceId, since, until);
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, window, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<Observation> observations = window.stream()
                .filter(observation -> visible.contains(observation.getId()))
                .toList();
        Map<UUID, String> deliveredGuidance = deliveredGuidanceByObservation(
                workspaceId, observations.stream().map(Observation::getId).collect(Collectors.toSet()));

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

        return edges.stream()
                .map(edge -> snapshot(
                        observations.stream()
                                .filter(observation ->
                                        !observation.getObservedAt().isAfter(edge))
                                .toList(),
                        eligiblePractices,
                        eligiblePracticesByGroup,
                        deliveredGuidance))
                .toList();
    }

    /**
     * Every practice the developer should see, whether or not it has anything to say, as of one edge.
     *
     * <p>The UNION of two sets, both needed. The eligible practices are what the workspace currently watches;
     * they belong here even with nothing to report, because "no observation reached this" and "the reviews ran
     * and found nothing" are different answers a developer cannot otherwise tell apart.
     * Practices with a standing are included even when review is no longer admitted for them: that
     * feedback was raised and delivered, and switching a practice off does not un-say it.
     */
    private StandingSnapshot snapshot(
            List<Observation> observations,
            List<Practice> eligiblePractices,
            Map<String, List<String>> eligiblePracticesByGroup,
            Map<UUID, String> deliveredGuidance) {
        Map<String, List<Observation>> byPractice = new LinkedHashMap<>();
        for (Observation observation : LatestRun.perClaim(observations)) {
            byPractice
                    .computeIfAbsent(observation.getPractice().getSlug(), ignored -> new ArrayList<>())
                    .add(observation);
        }
        Map<String, Practice> subjects = new LinkedHashMap<>();
        eligiblePractices.forEach(practice -> subjects.put(practice.getSlug(), practice));
        byPractice.forEach(
                (slug, group) -> subjects.putIfAbsent(slug, group.getFirst().getPractice()));

        List<StandingSnapshot.PracticeStanding> standings = new ArrayList<>();
        for (Map.Entry<String, Practice> subject : subjects.entrySet()) {
            String slug = subject.getKey();
            List<Observation> group = byPractice.getOrDefault(slug, List.of());
            PracticeEvidence evidence = group.isEmpty() ? null : PracticeEvidence.classify(group);
            List<Observation> observed = evidence == null ? List.of() : evidence.observed();
            PracticeTrend trend = practiceTrendService.calculatePractice(slug, observed);
            Double share = evidence != null && evidence.hasStanding() ? standingShare(evidence, trend) : null;
            PracticeStandingDTO dto = evidence != null && share != null
                    ? toStanding(evidence, trend, deliveredGuidance, share)
                    : silentStanding(subject.getValue(), evidence);
            standings.add(new StandingSnapshot.PracticeStanding(dto, observed, trend, share));
        }
        standings.sort(Comparator.<StandingSnapshot.PracticeStanding>comparingInt(
                        standing -> standingRank(standing.dto().standing()))
                .thenComparingInt(standing -> worstSeverityOrdinal(standing.dto())));
        Map<String, StandingSnapshot.PracticeStanding> practices = new LinkedHashMap<>();
        standings.forEach(standing -> practices.put(standing.dto().slug(), standing));
        return new StandingSnapshot(practices, eligiblePracticesByGroup);
    }

    /**
     * A practice with nothing to report, carrying WHICH silence it is.
     *
     * <p>{@code NO_OPPORTUNITY} outranks {@code NOT_OBSERVED}: a review that ran and found nothing to say is a
     * working instrument, not an unconfigured one.
     *
     * <p>No trend either: a direction over evidence that produced no verdict would be a claim about nothing.
     */
    private static PracticeStandingDTO silentStanding(Practice practice, @Nullable PracticeEvidence evidence) {
        boolean exercised = evidence != null && !evidence.withoutVerdict().isEmpty();
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

    /** One practice response, complete on first construction. */
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
     * <p>One rule over the newest {@link #STANDING_WINDOW} opportunities, weighted by recency: the unit is a
     * piece of reviewed work, and the denominator is the opportunities it had.
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
     * census.
     */
    private record PracticeEvidence(
            Practice practice,
            List<Observation> problems,
            List<Observation> strengths,
            List<Observation> withoutVerdict) {
        /**
         * Partitions each observation by its own kind; both positive shapes support the standing. Every problem
         * is kept, worst severity first: {@code recurrenceKey} hashes the artifact, so a locus is single-artifact
         * by construction and nothing is withheld for lack of corroboration.
         */
        static PracticeEvidence classify(List<Observation> group) {
            Practice practice = group.get(0).getPractice();
            Map<ObservationKind, List<Observation>> byOutcome =
                    group.stream().collect(Collectors.groupingBy(ObservationKind::of));
            List<Observation> demonstrated = bucket(byOutcome, ObservationKind.DEMONSTRATED_STRENGTH);
            List<Observation> avoided = bucket(byOutcome, ObservationKind.SAFE_AVOIDANCE);
            return new PracticeEvidence(
                    practice,
                    Stream.concat(
                                    bucket(byOutcome, ObservationKind.COMMISSION_PROBLEM).stream(),
                                    bucket(byOutcome, ObservationKind.OMISSION_GAP).stream())
                            .sorted(Comparator.comparingInt(PracticeStandingService::severityOrdinal))
                            .toList(),
                    Stream.concat(demonstrated.stream(), avoided.stream()).toList(),
                    Stream.concat(
                                    bucket(byOutcome, ObservationKind.NOT_APPLICABLE).stream(),
                                    bucket(byOutcome, ObservationKind.UNDETERMINED).stream())
                            .toList());
        }

        private static List<Observation> bucket(
                Map<ObservationKind, List<Observation>> byOutcome, ObservationKind outcome) {
            return byOutcome.getOrDefault(outcome, List.of());
        }

        /**
         * Everything the practice's latest runs said, verdict or not. This is the trend's input.
         *
         * <p>The verdictless rows belong here even though they can never move a direction: the bundler drops
         * an opportunity that produced no verdict, so they change no share and no posterior, but a piece of
         * reviewed work the practice looked at and could not judge is visible as an opportunity that yielded
         * nothing rather than as work that was never reviewed. That is the same distinction
         * {@code NO_OPPORTUNITY} draws one level up.
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
     * One developer's practices as of one moment, verdicts first and worst first, then the silences.
     *
     * @param practices every practice the developer should see, keyed by slug in the order the page lists them
     * @param eligiblePracticesByGroup the slugs of the practices review is admitted for, per group slug
     */
    public record StandingSnapshot(
            Map<String, PracticeStanding> practices, Map<String, List<String>> eligiblePracticesByGroup) {
        static final StandingSnapshot EMPTY = new StandingSnapshot(Map.of(), Map.of());

        /**
         * One practice as the snapshot read it.
         *
         * @param dto the developer-facing response
         * @param evidence everything the practice's latest runs said, verdict or not, newest first; empty for a
         *     practice nothing reached
         * @param trend the trend the standing was read off, for a reader that needs the opportunities behind a
         *     standing rather than the label — which practices are holding, and over how many pieces of work
         * @param share the continuous standing of a practice that has one, else null. The level above
         *     aggregates THIS rather than the rendered labels, which would put 0.79 and 0.51 at the same
         *     weight. Kept out of {@link PracticeStandingDTO}: the developer-facing response carries no score.
         */
        public record PracticeStanding(
                PracticeStandingDTO dto,
                List<Observation> evidence,
                PracticeTrend trend,
                @Nullable Double share) {}

        public List<PracticeStandingDTO> dtos() {
            return practices.values().stream().map(PracticeStanding::dto).toList();
        }

        /**
         * Whether a practice at {@code STRENGTH} is holding: every one of the newest opportunities its standing
         * was read off came back clean, not merely enough of them. The standing's window is the bar, so a
         * strength carried by an old slip that has decayed out of weight does not read as held.
         */
        public boolean isHolding(PracticeStandingDTO practice, PracticeTrend.CleanWork cleanWork) {
            return practice.standing() == PracticeStandingDTO.Standing.STRENGTH
                    && cleanWork.count() >= Math.min(STANDING_WINDOW, cleanWork.applicableWork());
        }
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
