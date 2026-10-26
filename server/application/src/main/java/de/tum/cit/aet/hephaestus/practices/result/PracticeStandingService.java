package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.PracticeStandingResultDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.TrendSupportDTO;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrend;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.result.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.review.ReviewOrder;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.StandingBasis;
import de.tum.cit.aet.hephaestus.practices.spi.TrendDirection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one reader of standings. The Practice profile reads one developer at chosen moments. Practices across the
 * workspace reads many developers at once, through the same selection, visibility and classification, so the two
 * pages cannot disagree about a developer.
 *
 * <p>Heph reads a processing-qualified snapshot on the worker, so this service is available wherever reviews are
 * prepared.
 */
@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class PracticeStandingService {

    private static final int MAX_FEEDBACK_PER_PRACTICE = 5;
    // Decay below 0.5 lets two clean opportunities outweigh two problem-bearing ones; regressions carry the same
    // weight.
    private static final double STANDING_DECAY = 0.4;

    private static final int MAX_STRENGTHS_PER_PRACTICE = 3;

    private static final SourceUsePurpose PURPOSE = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;

    private final ResultViewQuery resultViewQuery;
    private final JsonMapper json;
    private final ResultRepository results;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final ResultVisibilityPolicy visibilityPolicy;
    private final PracticeRepository practiceRepository;
    private final PracticeTrendService practiceTrendService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PracticeStandingDTO> getStandings(Long workspaceId) {
        return getStandingSnapshot(workspaceId).dtos();
    }

    public StandingSnapshot getStandingSnapshot(Long workspaceId) {
        return currentDeveloperLookup
                .currentDeveloperId()
                .map(developerId -> getStandingSnapshots(workspaceId, developerId, List.of(clock.instant()))
                        .getFirst())
                .orElse(StandingSnapshot.EMPTY);
    }

    /**
     * The developer's standings as they stood at each of {@code edges}, in that order, from one load of the
     * look-back up to the newest edge.
     *
     * <p>An edge bounds only the evidence: the look-back start, the eligible practices, the guidance and the
     * trend horizon are today's, so two snapshots of one developer differ in exactly the results recorded
     * between the two moments. That is the property the practice profile's "what changed" reads off them.
     */
    public List<StandingSnapshot> getStandingSnapshots(Long workspaceId, Long developerId, List<Instant> edges) {
        return standingSnapshots(workspaceId, developerId, edges, SourceReadAudience.HUMAN_RECORDED_STANDING);
    }

    /**
     * Standings for model context. The selection and classification are the profile's, but every supporting row
     * needs current, verifiable proof and permission for conversational processing. Human-only historical
     * assessments and a person's No AI choice never enter this read.
     */
    public List<StandingSnapshot> getProcessingStandingSnapshots(
            Long workspaceId, Long developerId, List<Instant> edges) {
        return standingSnapshots(workspaceId, developerId, edges, SourceReadAudience.PROCESSING);
    }

    private List<StandingSnapshot> standingSnapshots(
            Long workspaceId, Long developerId, List<Instant> edges, SourceReadAudience audience) {
        // Every edge reads the same work, so its sources are checked once for all of them.
        return visibilityPolicy.asOneRead(
                workspaceId, () -> readStandingSnapshots(workspaceId, developerId, edges, audience));
    }

    private List<StandingSnapshot> readStandingSnapshots(
            Long workspaceId, Long developerId, List<Instant> edges, SourceReadAudience audience) {
        SourceUsePurpose purpose = purpose(audience);
        // One instant for the evidence and the trend, so no result lies inside the read but before the trend.
        Instant since = practiceTrendService.horizon();
        List<ResultView> window = load(workspaceId, List.of(developerId), since, Collections.max(edges));
        Set<UUID> visible = visibilityPolicy.permitsHistory(workspaceId, rowsOf(window), purpose);
        Map<UUID, String> deliveredGuidance = deliveredGuidanceByResult(
                workspaceId,
                window.stream()
                        .map(view -> view.result().getId())
                        .filter(visible::contains)
                        .collect(Collectors.toSet()));
        Eligibility eligibility = eligibility(workspaceId);

        return edges.stream()
                .map(edge -> {
                    StandingRows rows = StandingRows.at(window, edge);
                    return snapshot(
                            rows,
                            readable(workspaceId, window, rows, edge, audience),
                            eligibility,
                            deliveredGuidance,
                            since);
                })
                .toList();
    }

    /**
     * Every given developer's standings now, over the evidence recorded from {@code since}, from one load of the
     * workspace rather than one profile read per developer. A developer nothing reached gets every eligible practice
     * silent.
     *
     * <p>The same selection and classification as {@link #getStandingSnapshots}, minus the delivered guidance: a
     * reader of the workspace sees counts, never what anyone was told. With the profile's look-back as
     * {@code since}, each developer's snapshot is the one their Practice profile shows now.
     */
    public WorkspaceStandings getWorkspaceStandings(Long workspaceId, Collection<Long> developerIds, Instant since) {
        Eligibility eligibility = eligibility(workspaceId);
        Instant now = clock.instant();
        List<ResultView> window = developerIds.isEmpty() ? List.of() : load(workspaceId, developerIds, since, now);
        StandingRows rows = StandingRows.at(window, now);
        // Counts quote nothing, so the workspace read skips the per-result delivery check that quotes need.
        StandingAccess readable = new StandingAccess(Set.of(), standingAccess(workspaceId, rows, now));
        Map<Long, StandingSnapshot> byDeveloper = new LinkedHashMap<>();
        for (Long developerId : developerIds) {
            byDeveloper.put(developerId, snapshot(rows.of(developerId), readable, eligibility, Map.of(), since));
        }
        Map<String, List<Practice>> practicesByGroup = new LinkedHashMap<>();
        eligibility.practices().stream()
                .sorted(Comparator.comparingInt(practice -> practice.getPosition()))
                .forEach(practice -> {
                    PracticeGroup group = practice.getGroup();
                    if (group != null) {
                        practicesByGroup
                                .computeIfAbsent(group.getSlug(), slug -> new ArrayList<>())
                                .add(practice);
                    }
                });
        return new WorkspaceStandings(byDeveloper, practicesByGroup);
    }

    /** The current standing over the profile's own look-back: what each developer's Practice profile shows now. */
    public WorkspaceStandings getCurrentWorkspaceStandings(Long workspaceId, Collection<Long> developerIds) {
        return getWorkspaceStandings(workspaceId, developerIds, practiceTrendService.horizon());
    }

    /**
     * Several developers' snapshots, and the practices review is admitted for per group slug, each group's in
     * catalog order: the same list whoever asks, so a reader's own evidence never adds or reorders a practice.
     */
    public record WorkspaceStandings(
            Map<Long, StandingSnapshot> byDeveloper, Map<String, List<Practice>> eligiblePracticesByGroup) {}

    private List<ResultView> load(Long workspaceId, Collection<Long> developerIds, Instant since, Instant until) {
        // Ended rows load too: the live run a requested review replaced still stands, and verdictless rows
        // distinguish NO_OPPORTUNITY from NOT_OBSERVED.
        return resultViewQuery
                .views(workspaceId, results.forDevelopersSince(workspaceId, developerIds, since, false))
                .stream()
                .filter(view -> !view.result().getRecordedAt().isAfter(until))
                .toList();
    }

    private static List<Result> rowsOf(Collection<ResultView> views) {
        return views.stream().map(ResultView::result).distinct().toList();
    }

    /**
     * What a standing at one moment reads: the claims held then, and each piece of work's live run that still
     * stands then.
     */
    private record StandingRows(List<ResultView> current, List<ResultView> live) {
        static StandingRows at(List<ResultView> window, Instant edge) {
            return new StandingRows(
                    window.stream()
                            .filter(view -> holdsClaimAt(view.result(), edge))
                            .toList(),
                    liveRunsAt(window, edge));
        }

        StandingRows of(Long developerId) {
            return new StandingRows(
                    current.stream()
                            .filter(view -> developerId.equals(view.result().getDeveloperId()))
                            .toList(),
                    live.stream()
                            .filter(view -> developerId.equals(view.result().getDeveloperId()))
                            .toList());
        }
    }

    /**
     * The rows each use may read: a standing reads {@link ResultVisibilityPolicy#permitsStandingAt}, a quoted
     * result also needs {@link ResultVisibilityPolicy#permitsAt}, which keeps every delivery control.
     */
    private record StandingAccess(Set<UUID> quotable, Set<UUID> standing) {}

    private StandingAccess readable(
            Long workspaceId, List<ResultView> window, StandingRows rows, Instant edge, SourceReadAudience audience) {
        Set<UUID> quotable = visibilityPolicy.permitsAt(workspaceId, rowsOf(window), purpose(audience), edge);
        return new StandingAccess(
                quotable,
                audience == SourceReadAudience.PROCESSING ? quotable : standingAccess(workspaceId, rows, edge));
    }

    private static SourceUsePurpose purpose(SourceReadAudience audience) {
        return audience == SourceReadAudience.PROCESSING ? SourceUsePurpose.CONVERSATIONAL_MENTORING : PURPOSE;
    }

    private Set<UUID> standingAccess(Long workspaceId, StandingRows rows, Instant edge) {
        return visibilityPolicy.permitsStandingAt(
                workspaceId,
                rowsOf(Stream.concat(rows.current().stream(), rows.live().stream())
                        .toList()),
                edge);
    }

    private static boolean holdsClaimAt(Result result, Instant edge) {
        return !result.getRecordedAt().isAfter(edge)
                && (result.getEndedAt() == null || result.getEndedAt().isAfter(edge));
    }

    /**
     * Each piece of work's live run that stands at {@code edge}. A requested or backfilled review takes over the
     * current claim on its work, yet it is a self-selected sample: it neither counts toward nor hides the live run
     * before it. So the claim held at {@code edge} is followed back through the claims it replaced, on the same
     * work version, to the newest live run. A newer live run is that run. A work change does not end the chain: the
     * newest decision about the work stands until a newer review of the work replaces it, even when a push started
     * no review of the practice (ADR 0065). A repair or a removal ends the chain. An outranked result never held
     * the claim.
     *
     * <p>A migrated predecessor remains historical assessment only. The classifier keeps it out of current
     * proof, quotes and trends. The results stay as recorded: this reads their end facts.
     */
    private static List<ResultView> liveRunsAt(List<ResultView> window, Instant edge) {
        Map<ClaimKey, List<ResultView>> claims = new LinkedHashMap<>();
        window.stream()
                .filter(view -> !view.result().getRecordedAt().isAfter(edge))
                .forEach(view -> claims.computeIfAbsent(ClaimKey.of(view.result()), key -> new ArrayList<>())
                        .add(view));
        List<ResultView> live = new ArrayList<>();
        claims.values().forEach(claim -> liveRunOf(claim, edge).ifPresent(live::add));
        return live;
    }

    private record ClaimKey(
            long practiceId, long itemId, @Nullable Long developerId) {
        static ClaimKey of(Result result) {
            return new ClaimKey(result.getPracticeId(), result.getItemId(), result.getDeveloperId());
        }
    }

    private static Optional<ResultView> liveRunOf(List<ResultView> claim, Instant edge) {
        List<ResultView> newestFirst = claim.stream()
                .sorted(Comparator.comparing(PracticeStandingService::order).reversed())
                .toList();
        ResultView holder = newestFirst.stream()
                .filter(view -> holdsClaimAt(view.result(), edge))
                .findFirst()
                .or(() -> workChanged(newestFirst.stream()))
                .orElse(null);
        while (holder != null && !isLive(holder)) {
            ResultView successor = holder;
            List<ResultView> older = newestFirst.stream()
                    .filter(view -> order(view).compareTo(order(successor)) < 0)
                    .toList();
            holder = older.stream()
                    .filter(view -> replacedBy(view.result(), successor.result()))
                    .findFirst()
                    .or(() -> workChanged(older.stream()))
                    .orElse(null);
        }
        return Optional.ofNullable(holder);
    }

    /** The newest of {@code newestFirst} that could hold the claim, when a work change ended it. */
    private static Optional<ResultView> workChanged(Stream<ResultView> newestFirst) {
        return newestFirst
                .filter(view -> view.result().getEndReason() != ResultEndReason.OUTRANKED)
                .findFirst()
                .filter(view -> view.result().getEndReason() == ResultEndReason.WORK_CHANGED);
    }

    // ResultClaimWriter ends the claim it replaces at the replacing result's recording, or at the replaced
    // result's own recording when that is later. That end time links a claim to the claim it replaced.
    private static boolean replacedBy(Result prior, Result successor) {
        Instant replacedAt = successor.getRecordedAt().isBefore(prior.getRecordedAt())
                ? prior.getRecordedAt()
                : successor.getRecordedAt();
        return prior.getEndReason() == ResultEndReason.REPLACED
                && replacedAt.equals(prior.getEndedAt())
                && Objects.equals(prior.getItemVersion(), successor.getItemVersion());
    }

    private static ReviewOrder order(ResultView view) {
        return new ReviewOrder(
                view.review().getOrderedAt(),
                view.result().getRecordedAt(),
                view.review().getId());
    }

    private static boolean isLive(ResultView view) {
        return view.review().getOrigin() == ReviewOrigin.EVENT || view.review().getOrigin() == ReviewOrigin.SCHEDULE;
    }

    private static Map<String, List<ResultView>> byPractice(Stream<ResultView> rows, Set<UUID> readable) {
        Map<String, List<ResultView>> byPractice = new LinkedHashMap<>();
        rows.filter(view -> readable.contains(view.result().getId()))
                .forEach(view -> byPractice
                        .computeIfAbsent(view.practice().getSlug(), ignored -> new ArrayList<>())
                        .add(view));
        return byPractice;
    }

    private Eligibility eligibility(Long workspaceId) {
        List<Practice> eligiblePractices = practiceRepository.findByWorkspaceId(workspaceId).stream()
                .filter(practice -> definition(practice).reviewedBy().by() == ReviewedByKind.HEPHAESTUS)
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
        return new Eligibility(eligiblePractices, eligiblePracticesByGroup);
    }

    private record Eligibility(List<Practice> practices, Map<String, List<String>> practicesByGroup) {}

    private PracticeDefinition definition(Practice practice) {
        return json.convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class);
    }

    /**
     * Every eligible practice, so absence of review stays distinct from review without a verdict.
     *
     * <p>Current reviews decide a practice they reached with a verdict, from the live runs first. Only a practice
     * no current review decided falls back to outcomes recorded before the current result model, marked as earlier
     * reviews. Such a standing quotes nothing and claims no trend: its rows are readable history, not current proof.
     */
    private StandingSnapshot snapshot(
            StandingRows rows,
            StandingAccess readable,
            Eligibility eligibility,
            Map<UUID, String> deliveredGuidance,
            Instant since) {
        Map<String, List<ResultView>> current = byPractice(
                rows.current().stream().filter(view -> view.result().getEvidenceVersion() != 0), readable.standing());
        Map<String, List<ResultView>> earlier = byPractice(
                Stream.concat(rows.current().stream(), rows.live().stream())
                        .filter(view -> view.result().getEvidenceVersion() == 0)
                        .distinct(),
                readable.standing());
        Map<String, List<ResultView>> live = byPractice(
                rows.live().stream().filter(view -> view.result().getEvidenceVersion() != 0), readable.standing());
        int window = practiceTrendService.standingWindow();

        List<StandingSnapshot.PracticeStandingSnapshot> standings = new ArrayList<>();
        for (Practice practice : eligibility.practices()) {
            String slug = practice.getSlug();
            PracticeEvidence reviewed = PracticeEvidence.classify(current.getOrDefault(slug, List.of()));
            PracticeEvidence recorded = PracticeEvidence.classify(earlier.getOrDefault(slug, List.of()));
            List<ResultView> liveRuns = live.getOrDefault(slug, List.of());
            PracticeTrend trend = practiceTrendService.calculateSelectedPractice(liveRuns, since);
            OptionalDouble liveShare = trend.recentMetShare(window, STANDING_DECAY);
            if (reviewed.hasStanding() || liveShare.isPresent()) {
                // A requested review without a verdict leaves a standing live runs still carry.
                double share = liveShare.isPresent()
                        ? liveShare.getAsDouble()
                        : practiceTrendService
                                .selfSelectedMetShare(reviewed.observed(), since, window, STANDING_DECAY)
                                .orElseGet(() -> reviewed.problems().isEmpty() ? 1.0 : 0.0);
                PracticeStandingDTO dto = toStanding(
                        practice,
                        reviewed,
                        StandingScale.classify(share),
                        trend.direction(),
                        TrendSupportDTO.from(trend.support()),
                        readable.quotable(),
                        deliveredGuidance,
                        StandingBasis.CURRENT_REVIEWS);
                standings.add(new StandingSnapshot.PracticeStandingSnapshot(
                        dto, reviewed.observed(), liveRuns, trend, share));
            } else if (recorded.hasStanding()) {
                double share = earlierShare(recorded, since, window);
                PracticeStandingDTO dto = toStanding(
                        practice,
                        recorded,
                        StandingScale.classify(share),
                        null,
                        null,
                        Set.of(),
                        Map.of(),
                        recorded.observed().stream()
                                        .anyMatch(view -> view.result().getPracticeVersionId() == null)
                                ? StandingBasis.EARLIER_REVIEWS_CRITERIA_UNKNOWN
                                : StandingBasis.EARLIER_REVIEWS);
                standings.add(new StandingSnapshot.PracticeStandingSnapshot(
                        dto, recorded.observed(), List.of(), trend, share));
            } else {
                List<ResultView> withoutVerdict = Stream.concat(
                                reviewed.withoutVerdict().stream(), recorded.withoutVerdict().stream())
                        .toList();
                standings.add(new StandingSnapshot.PracticeStandingSnapshot(
                        silentStanding(practice, withoutVerdict), withoutVerdict, liveRuns, trend, null));
            }
        }
        standings.sort(Comparator.<StandingSnapshot.PracticeStandingSnapshot>comparingInt(
                        standing -> StandingScale.rank(standing.dto().standing()))
                .thenComparingInt(standing -> worstSeverityRank(standing.dto())));
        Map<String, StandingSnapshot.PracticeStandingSnapshot> practices = new LinkedHashMap<>();
        standings.forEach(standing -> practices.put(standing.dto().slug(), standing));
        return new StandingSnapshot(practices, eligibility.practicesByGroup());
    }

    // The live runs first, as for current reviews; requested and backfilled ones only without a live run.
    private double earlierShare(PracticeEvidence recorded, Instant since, int window) {
        Map<Boolean, List<ResultView>> byLiveness =
                recorded.observed().stream().collect(Collectors.partitioningBy(PracticeStandingService::isLive));
        OptionalDouble live = practiceTrendService.selectedMetShare(
                byLiveness.getOrDefault(true, List.of()), since, window, STANDING_DECAY);
        if (live.isPresent()) {
            return live.getAsDouble();
        }
        return practiceTrendService
                .selectedMetShare(byLiveness.getOrDefault(false, List.of()), since, window, STANDING_DECAY)
                .orElseGet(() -> recorded.problems().isEmpty() ? 1.0 : 0.0);
    }

    // Evidence without a verdict cannot establish a trend direction.
    private PracticeStandingDTO silentStanding(Practice practice, List<ResultView> withoutVerdict) {
        boolean exercised = !withoutVerdict.isEmpty();
        boolean doesNotApplyOnly = exercised
                && withoutVerdict.stream().allMatch(view -> view.result().getOutcome() == ResultOutcome.DOES_NOT_APPLY);
        PracticeGroup group = practice.getGroup();
        PracticeDefinition definition = definition(practice);
        return new PracticeStandingDTO(
                practice.getSlug(),
                definition.name(),
                group != null ? group.getSlug() : null,
                group != null ? group.getName() : null,
                definition.guidance().whyItMatters(),
                definition.guidance().whatGoodLooksLike(),
                exercised ? PracticeStanding.NO_OPPORTUNITY : PracticeStanding.NOT_OBSERVED,
                List.of(),
                List.of(),
                null,
                null,
                doesNotApplyOnly,
                null);
    }

    private PracticeStandingDTO toStanding(
            Practice practice,
            PracticeEvidence evidence,
            PracticeStanding standing,
            @Nullable TrendDirection direction,
            @Nullable TrendSupportDTO trendSupport,
            Set<UUID> quotable,
            Map<UUID, String> deliveredGuidance,
            StandingBasis basis) {
        PracticeGroup group = practice.getGroup();
        PracticeDefinition definition = definition(practice);
        return new PracticeStandingDTO(
                practice.getSlug(),
                definition.name(),
                group != null ? group.getSlug() : null,
                group != null ? group.getName() : null,
                definition.guidance().whyItMatters(),
                definition.guidance().whatGoodLooksLike(),
                standing,
                feedback(evidence.problems(), MAX_FEEDBACK_PER_PRACTICE, quotable, deliveredGuidance),
                feedback(evidence.strengths(), MAX_STRENGTHS_PER_PRACTICE, quotable, deliveredGuidance),
                direction,
                trendSupport,
                false,
                basis);
    }

    private List<PracticeStandingResultDTO> feedback(
            List<ResultView> practiceResults, int cap, Set<UUID> quotable, Map<UUID, String> deliveredGuidance) {
        return practiceResults.stream()
                .filter(view -> quotable.contains(view.result().getId()))
                .limit(cap)
                .map(view -> new PracticeStandingResultDTO(
                        view.result().getId(),
                        view.result().getSummary() == null
                                ? view.item().label()
                                : view.result().getSummary(),
                        deliveredGuidance.get(view.result().getId()),
                        view.result().getSeverity(),
                        view.result().getOutcome(),
                        view.item(),
                        view.review().getOrigin()))
                .toList();
    }

    public int standingWindow() {
        return practiceTrendService.standingWindow();
    }

    private record PracticeEvidence(
            List<ResultView> problems, List<ResultView> strengths, List<ResultView> withoutVerdict) {
        static PracticeEvidence classify(List<ResultView> group) {
            Map<ResultOutcome, List<ResultView>> byOutcome = group.stream()
                    .collect(Collectors.groupingBy(view -> view.result().getOutcome()));
            return new PracticeEvidence(
                    bucket(byOutcome, ResultOutcome.NOT_MET).stream()
                            .sorted(Comparator.comparingInt(PracticeStandingService::severityRank))
                            .toList(),
                    bucket(byOutcome, ResultOutcome.MET),
                    Stream.concat(
                                    bucket(byOutcome, ResultOutcome.DOES_NOT_APPLY).stream(),
                                    bucket(byOutcome, ResultOutcome.COULD_NOT_DETERMINE).stream())
                            .toList());
        }

        private static List<ResultView> bucket(Map<ResultOutcome, List<ResultView>> byOutcome, ResultOutcome outcome) {
            return byOutcome.getOrDefault(outcome, List.of());
        }

        // Preserve verdictless reviewed work so it remains distinct from work never reviewed.
        List<ResultView> observed() {
            return Stream.concat(Stream.concat(problems.stream(), strengths.stream()), withoutVerdict.stream())
                    .toList();
        }

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
            Map<String, PracticeStandingSnapshot> practices, Map<String, List<String>> eligiblePracticesByGroup) {
        public static final StandingSnapshot EMPTY = new StandingSnapshot(Map.of(), Map.of());

        /**
         * One practice as the snapshot read it.
         *
         * @param dto the developer-facing response
         * @param evidence the results the standing was read from, verdict or not, in the order
         *     {@link PracticeEvidence#observed()} builds it: problems worst severity first, then strengths, then
         *     the rows that reached no verdict; earlier reviews only for a standing read from them; empty for a
         *     practice nothing reached
         * @param liveRuns each piece of work's live run that still stands, also one a requested or backfilled review
         *     replaced as the current claim: what the trend counts
         * @param trend the trend the standing was read off, for a reader that needs the opportunities behind a
         *     standing rather than the label — which practices are holding, and over how many pieces of work
         * @param share the continuous standing of a practice that has one, else null. The level above
         *     aggregates THIS rather than the rendered labels, which would put 0.79 and 0.51 at the same
         *     weight. Kept out of {@link PracticeStandingDTO}: the developer-facing response carries no score.
         */
        public record PracticeStandingSnapshot(
                PracticeStandingDTO dto,
                List<ResultView> evidence,
                List<ResultView> liveRuns,
                PracticeTrend trend,
                @Nullable Double share) {

            /**
             * Whether a practice at {@code STRENGTH} is holding: every one of the newest opportunities its
             * standing was read off came back clean, not merely enough of them. The standing's window is the
             * bar, so a strength carried by an old slip that has decayed out of weight does not read as held.
             *
             * @param window how many of the newest opportunities a standing is read from
             */
            public boolean isHolding(int window) {
                PracticeTrend.CleanWork cleanWork = trend.cleanWork();
                return dto.standing() == PracticeStanding.STRENGTH
                        && cleanWork.count() >= WorkResolution.CLEAN_NEEDED
                        && cleanWork.count() >= Math.min(window, cleanWork.decidedWork());
            }
        }

        public List<PracticeStandingDTO> dtos() {
            return practices.values().stream()
                    .map(PracticeStandingSnapshot::dto)
                    .toList();
        }
    }

    // Practice-page feedback covers several items and cannot describe guidance for one result.
    private Map<UUID, String> deliveredGuidanceByResult(Long ws, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return results.deliveredGuidance(ws, ids).stream()
                .collect(Collectors.toMap(
                        ResultRepository.DeliveredGuidance::getResultId, ResultRepository.DeliveredGuidance::getBody));
    }

    private static final int UNRANKED = Integer.MAX_VALUE;

    private static int worstSeverityRank(PracticeStandingDTO practiceStanding) {
        return practiceStanding.toWorkOn().stream()
                .mapToInt(result -> rankOf(result.severity()))
                .min()
                .orElse(UNRANKED);
    }

    private static int severityRank(ResultView view) {
        return rankOf(view.result().getSeverity());
    }

    private static int rankOf(@Nullable Severity severity) {
        return severity == null ? UNRANKED : severity.rank();
    }
}
