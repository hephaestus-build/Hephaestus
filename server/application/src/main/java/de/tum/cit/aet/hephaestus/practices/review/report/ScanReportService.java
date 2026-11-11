package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewOrder;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.dto.BoundModelSlotDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.FrozenModelDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewMethodDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReasonCountDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ProblemChangesDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.RemovalGuardDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanCountsDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanExcludedFileDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanPracticeReportDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Comparison;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import de.tum.cit.aet.hephaestus.practices.spi.RemovalGuardState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanReportService {
    private static final int READ_BATCH = 256;
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ResultRepository results;
    private final PracticeVersionRepository versions;
    private final ProblemComparison comparison;
    private final ScanEvidencePolicy eligibility;
    private final ScanComparisonService pairComparison;
    private final ScanReportRepository rows;
    private final JsonMapper json;
    private final FeedbackRetentionProperties retention;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entities;

    @Transactional
    public FrozenScanReport freeze(long workspaceId, UUID reviewId) {
        var review = require(workspaceId, reviewId);
        reviews.flush();
        var report = calculate(review, true);
        review.setReport(json.valueToTree(report));
        return report;
    }

    public FrozenScanReport recorded(Review review) {
        return review.getReport() == null
                ? calculate(review, false)
                : json.convertValue(review.getReport(), FrozenScanReport.class);
    }

    public Review require(long workspaceId, UUID reviewId) {
        return reviews.findByIdAndWorkspaceId(reviewId, workspaceId)
                .filter(r -> r.getKind() == ReviewKind.SCAN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The scan does not exist."));
    }

    /** When a completed scan's raw proof expires: the retention of what Hephaestus says about a person. */
    public @Nullable Instant expiresAt(Review review) {
        return review.getCompletedAt() == null ? null : review.getCompletedAt().plus(retention.feedbackRetention());
    }

    public boolean expired(Review review) {
        Instant expiresAt = expiresAt(review);
        return (expiresAt != null && !clock.instant().isBefore(expiresAt))
                || (review.getReport() != null
                        && review.getReport().path("expired").asBoolean());
    }

    public @Nullable List<ScanExcludedFileDTO> exclusions(Review review) {
        var receipt = review.getInventoryReceipt();
        if (expired(review) || receipt == null || !receipt.path("exclusions").isArray()) return null;
        var excluded = new ArrayList<ScanExcludedFileDTO>();
        for (var file : receipt.path("exclusions")) excluded.add(json.convertValue(file, ScanExcludedFileDTO.class));
        return List.copyOf(excluded);
    }

    public void requireRaw(Review review) {
        if (expired(review)) throw new ResponseStatusException(HttpStatus.GONE, "The scan's raw proof has expired.");
    }

    public Map<String, Long> markedCounts(Review review) {
        return rows.markedCounts(review.getWorkspaceId(), review.getId()).stream()
                .collect(Collectors.toMap(
                        ScanReportRepository.KindCount::getKind, ScanReportRepository.KindCount::getCount));
    }

    public ScanCountsDTO currentCounts(Review review) {
        var count = recorded(review).recordedCounts();
        return withOpen(count, expired(review) ? 0 : openProblems(review, null));
    }

    public Map<UUID, ScanCountsDTO> currentCounts(long workspaceId, Map<Review, FrozenScanReport> recorded) {
        var reviewIds = recorded.keySet().stream()
                .filter(r -> !expired(r))
                .map(Review::getId)
                .toList();
        var open = new HashMap<UUID, Integer>();
        UUID after = null;
        while (!reviewIds.isEmpty()) {
            var ids = reviews.proofIds(workspaceId, reviewIds, after, READ_BATCH, null, true);
            if (ids.isEmpty()) break;
            var rows = results.findByWorkspaceIdAndIdIn(workspaceId, ids);
            var allowed = eligibility.permitted(workspaceId, rows);
            if (!allowed.isEmpty()) {
                this.rows
                        .openCounts(workspaceId, reviewIds, allowed)
                        .forEach(row -> open.merge(row.getReviewId(), Math.toIntExact(row.getCount()), Math::addExact));
            }
            rows.forEach(this::detach);
            if (ids.size() < READ_BATCH) break;
            after = ids.getLast();
        }
        return recorded.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().getId(),
                        e -> withOpen(
                                e.getValue().recordedCounts(),
                                open.getOrDefault(e.getKey().getId(), 0))));
    }

    public ScanPracticeReportDTO currentPractice(Review review, ScanPracticeReportDTO practice) {
        return new ScanPracticeReportDTO(
                practice.practiceId(),
                practice.practiceVersionId(),
                practice.number(),
                practice.practiceSlug(),
                practice.practiceName(),
                practice.reviewDigest(),
                withOpen(
                        practice.counts(),
                        expired(review) ? 0 : openProblems(review, Long.parseLong(practice.practiceId()))),
                practice.listingComplete(),
                practice.method(),
                practice.comparison(),
                practice.status(),
                practice.reason());
    }

    private static ScanCountsDTO withOpen(ScanCountsDTO c, int open) {
        return new ScanCountsDTO(
                c.files(),
                c.practiceItemPairs(),
                c.reviewed(),
                c.reused(),
                c.met(),
                c.notMet(),
                c.doesNotApply(),
                c.couldNotDetermine(),
                c.problems(),
                open,
                c.withheld(),
                c.notReviewed());
    }

    private int openProblems(Review review, @Nullable Long practiceId) {
        int[] count = {0};
        eachProof(review, practiceId, true, rows -> {
            var allowed = eligibility.permitted(review, rows);
            if (allowed.isEmpty()) return;
            count[0] =
                    Math.addExact(count[0], Math.toIntExact(this.rows.problemCount(review.getWorkspaceId(), allowed)));
        });
        return count[0];
    }

    public int withheldProofPairs(Review review) {
        int[] denied = {0};
        eachProof(
                review,
                null,
                false,
                rows -> denied[0] = Math.addExact(
                        denied[0],
                        rows.size() - eligibility.permitted(review, rows).size()));
        return denied[0];
    }

    private void eachProof(Review review, @Nullable Long practiceId, boolean openOnly, Consumer<List<Result>> read) {
        UUID after = null;
        while (true) {
            var ids = reviews.proofIds(
                    review.getWorkspaceId(), List.of(review.getId()), after, READ_BATCH, practiceId, openOnly);
            if (ids.isEmpty()) return;
            var rows = results.findByWorkspaceIdAndIdIn(review.getWorkspaceId(), ids);
            read.accept(rows);
            rows.forEach(this::detach);
            if (ids.size() < READ_BATCH) return;
            after = ids.getLast();
        }
    }

    private ScanCountsDTO counts(Review review, @Nullable Long practiceId, boolean complete) {
        var values = rows.counts(review.getWorkspaceId(), review.getId(), practiceId);
        var count = new ScanCountsDTO(
                complete ? Math.toIntExact(values.getFiles()) : null,
                complete ? Math.toIntExact(values.getPairs()) : null,
                Math.toIntExact(values.getReviewed()),
                Math.toIntExact(values.getReused()),
                Math.toIntExact(values.getMet()),
                Math.toIntExact(values.getNotMet()),
                Math.toIntExact(values.getDoesNotApply()),
                Math.toIntExact(values.getCouldNotDetermine()),
                0,
                0,
                Math.toIntExact(values.getWithheld()),
                review.getStatus().isTerminal() ? Math.toIntExact(values.getPending()) : 0);
        int children = Math.toIntExact(rows.childCount(review.getWorkspaceId(), review.getId(), practiceId));
        return new ScanCountsDTO(
                count.files(),
                count.practiceItemPairs(),
                count.reviewed(),
                count.reused(),
                count.met(),
                count.notMet(),
                count.doesNotApply(),
                count.couldNotDetermine(),
                children,
                expired(review) ? 0 : openProblems(review, practiceId),
                count.withheld(),
                count.notReviewed());
    }

    private FrozenScanReport calculate(Review review, boolean close) {
        var assigned =
                assignments.findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(review.getWorkspaceId(), review.getId());
        boolean listing = review.getInventoryReceipt() != null
                && review.getInventoryReceipt().path("complete").asBoolean()
                && assigned.stream().allMatch(p -> Boolean.TRUE.equals(p.getListingComplete()));
        var baseline = baseline(review);
        var previous = baseline == null
                ? Map.<Long, ReviewPractice>of()
                : assignments
                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(review.getWorkspaceId(), baseline.getId())
                        .stream()
                        .collect(Collectors.toMap(ReviewPractice::getPracticeId, Function.identity()));
        var practiceReports = new ArrayList<ScanPracticeReportDTO>();
        var comparisons = new HashMap<Long, Comparison>();
        for (var assignment : assigned) {
            var version = versions.findByIdAndWorkspaceId(
                            Objects.requireNonNull(assignment.getPracticeVersionId()), review.getWorkspaceId())
                    .filter(v -> v.getWorkspaceId() == review.getWorkspaceId())
                    .orElseThrow();
            var definition = json.convertValue(version.getDefinition(), PracticeDefinition.class);
            var prior = previous.get(assignment.getPracticeId());
            var comparable = ProblemComparison.comparability(
                    standard(prior),
                    Objects.requireNonNull(standard(assignment)),
                    prior != null
                            && Boolean.TRUE.equals(prior.getListingComplete())
                            && baseline != null
                            && !expired(baseline));
            comparisons.put(assignment.getPracticeId(), comparable);
            practiceReports.add(new ScanPracticeReportDTO(
                    Long.toString(assignment.getPracticeId()),
                    Long.toString(Objects.requireNonNull(version.getId())),
                    version.getNumber(),
                    version.getPractice().getSlug(),
                    definition.name(),
                    version.getReviewDigest(),
                    counts(review, assignment.getPracticeId(), Boolean.TRUE.equals(assignment.getListingComplete())),
                    Boolean.TRUE.equals(assignment.getListingComplete()),
                    method(Objects.requireNonNull(assignment.getMethod())),
                    close ? comparable : Comparison.INCOMPLETE,
                    assignment.getStatus(),
                    assignment.getReason()));
        }
        var changes = close
                ? pairComparison.compare(review, baseline, comparisons)
                : new EnumMap<ProblemBaselineState, Integer>(ProblemBaselineState.class);
        var kinds = comparisons.values().stream().distinct().toList();
        var comparable = !close
                ? Comparison.INCOMPLETE
                : kinds.size() == 1
                        ? kinds.getFirst()
                        : kinds.isEmpty()
                                ? baseline == null ? Comparison.FIRST_SCAN : Comparison.INCOMPLETE
                                : Comparison.MIXED;
        var receipt = review.getInventoryReceipt();
        var removal = new RemovalGuardDTO(
                receipt == null
                        ? RemovalGuardState.INCOMPLETE_LISTING
                        : RemovalGuardState.valueOf(receipt.path("removalGuard").asString("INCOMPLETE_LISTING")),
                receipt == null ? 0 : receipt.path("previousFiles").asInt(),
                receipt == null ? 0 : receipt.path("wouldRemove").asInt());
        return new FrozenScanReport(
                1,
                listing,
                baseline == null ? null : baseline.getId(),
                comparable,
                counts(review, null, listing),
                new ProblemChangesDTO(
                        changes.getOrDefault(ProblemBaselineState.NEW, 0),
                        changes.getOrDefault(ProblemBaselineState.UNCHANGED, 0),
                        changes.getOrDefault(ProblemBaselineState.UPDATED, 0),
                        changes.getOrDefault(ProblemBaselineState.ABSENT, 0)),
                practiceReports,
                reasons(review, ReviewItemState.WITHHELD),
                review.getStatus().isTerminal() ? reasons(review, ReviewItemState.LISTED) : List.of(),
                removal,
                false,
                0,
                0);
    }

    private ProblemComparison.@Nullable Standard standard(@Nullable ReviewPractice assignment) {
        if (assignment == null || assignment.getPracticeVersionId() == null || assignment.getMethod() == null)
            return null;
        var version = versions.findByIdAndWorkspaceId(assignment.getPracticeVersionId(), assignment.getWorkspaceId())
                .orElseThrow();
        var definition = json.convertValue(version.getDefinition(), PracticeDefinition.class);
        return new ProblemComparison.Standard(
                ProblemComparison.sha256(
                        json.writeValueAsString(comparison.canonical(json.valueToTree(definition.scan())))),
                version.getReviewDigest(),
                assignment.getMethod().modelDigest(),
                assignment.getMethod().epoch());
    }

    private @Nullable Review baseline(Review current) {
        return reviews
                .findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(current.getWorkspaceId(), current.getItemId())
                .stream()
                .filter(r -> r.getKind() == ReviewKind.SCAN
                        && r.getStatus().isTerminal()
                        && !r.getId().equals(current.getId())
                        && new ReviewOrder(r.getOrderedAt(), r.getCreatedAt(), r.getId())
                                        .compareTo(new ReviewOrder(
                                                current.getOrderedAt(), current.getCreatedAt(), current.getId()))
                                < 0
                        && r.getChecks()
                                .path("ref")
                                .asString()
                                .equals(current.getChecks().path("ref").asString()))
                .findFirst()
                .orElse(null);
    }

    private List<ReasonCountDTO> reasons(Review review, ReviewItemState state) {
        return rows
                .reasons(
                        review.getWorkspaceId(),
                        review.getId(),
                        state.name(),
                        review.getStopReason() == null
                                ? "EXECUTION_INCOMPLETE"
                                : review.getStopReason().name())
                .stream()
                .map(row -> new ReasonCountDTO(
                        row.getReason(),
                        row.getReason().replace('_', ' ').toLowerCase(Locale.ROOT),
                        Math.toIntExact(row.getCount())))
                .toList();
    }

    public static ReviewMethodDTO method(ReviewMethod method) {
        return new ReviewMethodDTO(
                method.epoch(),
                method.modelDigest(),
                method.models().stream()
                        .map(s -> new BoundModelSlotDTO(
                                s.slot(),
                                s.kind(),
                                s.model() == null
                                        ? null
                                        : new FrozenModelDTO(
                                                s.model().modelId(),
                                                s.model().providerProtocol(),
                                                s.model().providerModelId(),
                                                s.model().reasoningEffort(),
                                                s.model().options())))
                        .toList());
    }

    private void detach(Object entity) {
        var context = Objects.requireNonNull(entities);
        if (context.contains(entity)) context.detach(entity);
    }

    @Transactional
    public void expire(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId)
                .filter(r -> r.getKind() == ReviewKind.SCAN)
                .orElseThrow();
        if (!expired(review)) throw new IllegalStateException("The raw proof is still retained.");
        var frozen = recorded(review);
        int count = rows.deleteReviewMembers(workspaceId, reviewId);
        review.setReport(json.valueToTree(new FrozenScanReport(
                frozen.schemaVersion(),
                frozen.listingComplete(),
                frozen.baselineScanId(),
                frozen.comparison(),
                frozen.recordedCounts(),
                frozen.problemChanges(),
                frozen.practices(),
                frozen.withheldReasons(),
                frozen.notReviewedReasons(),
                frozen.removalGuard(),
                true,
                count,
                frozen.redactedProofPairs())));
    }

    @Transactional
    public int eraseResultReferences(long workspaceId, Collection<UUID> resultIds) {
        if (resultIds.isEmpty()) return 0;
        var ids = resultIds.toArray(UUID[]::new);
        var affected = rows.affectedReviewCounts(workspaceId, ids);
        var frozen = new HashMap<UUID, FrozenScanReport>();
        for (var entry : affected) {
            var review = reviews.lockByIdAndWorkspaceId(entry.getReviewId(), workspaceId)
                    .orElseThrow();
            if (review.getKind() == ReviewKind.SCAN) frozen.put(entry.getReviewId(), recorded(review));
        }
        rows.eraseProblemComparisons(workspaceId, ids);
        int deleted = rows.deleteResultMembers(workspaceId, ids);
        for (var entry : affected) {
            var f = frozen.get(entry.getReviewId());
            if (f == null) continue;
            var review = reviews.findByIdAndWorkspaceId(entry.getReviewId(), workspaceId)
                    .orElseThrow();
            review.setReport(json.valueToTree(new FrozenScanReport(
                    f.schemaVersion(),
                    f.listingComplete(),
                    f.baselineScanId(),
                    f.comparison(),
                    f.recordedCounts(),
                    f.problemChanges(),
                    f.practices(),
                    f.withheldReasons(),
                    f.notReviewedReasons(),
                    f.removalGuard(),
                    f.expired(),
                    f.expiredProofPairs(),
                    Math.addExact(f.redactedProofPairs(), Math.toIntExact(entry.getCount())))));
        }
        return deleted;
    }
}
