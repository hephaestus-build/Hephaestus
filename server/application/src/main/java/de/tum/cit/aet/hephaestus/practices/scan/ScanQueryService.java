package de.tum.cit.aet.hephaestus.practices.scan;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewShownStatus;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.FrozenScanReport;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanAvailabilityDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanCountsDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanDetailDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanPracticeRefDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanProgressDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanReportDTO;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ScanSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanQueryService {
    private final ReviewRepository reviews;
    private final ReviewService service;
    private final ReviewRuntimeQuery runtime;
    private final ScanReportService reports;
    private final ProductReferenceQuery references;

    public Page<ScanSummaryDTO> list(
            long workspaceId, @Nullable Long repositoryItemId, @Nullable ReviewStatus status, Pageable pageable) {
        var page = reviews.findScans(
                ReviewKind.SCAN.name(), workspaceId, repositoryItemId, status == null ? null : status.name(), pageable);
        if (page.isEmpty()) return page.map(this::summary);
        var jobs =
                runtime.jobCounts(workspaceId, page.stream().map(Review::getId).toList());
        var refs = references.items(
                workspaceId, page.stream().map(Review::getItemId).distinct().toList());
        var accounts = references.accounts(
                workspaceId,
                page.stream()
                        .map(Review::getRequestedById)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        var recorded = page.stream().collect(Collectors.toMap(Function.identity(), reports::recorded));
        var counts = reports.currentCounts(workspaceId, recorded);
        return page.map(r -> summary(
                r,
                Objects.requireNonNull(refs.get(r.getItemId())),
                r.getRequestedById() == null ? null : accounts.get(r.getRequestedById()),
                Objects.requireNonNull(recorded.get(r)),
                Objects.requireNonNull(counts.get(r.getId())),
                jobs.getOrDefault(r.getId(), Map.of())));
    }

    public ScanSummaryDTO summary(Review review) {
        var frozen = reports.recorded(review);
        var refs = references.items(review.getWorkspaceId(), List.of(review.getItemId()));
        var account = review.getRequestedById() == null
                ? null
                : references
                        .accounts(review.getWorkspaceId(), List.of(review.getRequestedById()))
                        .get(review.getRequestedById());
        return summary(
                review,
                Objects.requireNonNull(refs.get(review.getItemId())),
                account,
                frozen,
                reports.currentCounts(review),
                runtime.jobCounts(review.getWorkspaceId(), List.of(review.getId()))
                        .getOrDefault(review.getId(), Map.of()));
    }

    private ScanSummaryDTO summary(
            Review review,
            ItemRefDTO repository,
            @Nullable AccountRefDTO account,
            FrozenScanReport frozen,
            ScanCountsDTO counts,
            Map<ReviewJobStatus, Long> jobs) {
        return new ScanSummaryDTO(
                review.getId(),
                repository,
                review.getChecks().path("ref").asString(),
                Objects.requireNonNull(review.getItemVersion()),
                review.getOrigin(),
                ReviewShownStatus.of(review.getStatus(), jobs),
                account,
                review.getCreatedAt(),
                review.getChecks().path("startedAt").isString()
                        ? Instant.parse(review.getChecks().path("startedAt").asString())
                        : null,
                review.getCompletedAt(),
                frozen.practices().size(),
                counts,
                frozen.baselineScanId(),
                review.getStopReason());
    }

    public ScanDetailDTO detail(long workspaceId, UUID id) {
        var review = reports.require(workspaceId, id);
        var summary = summary(review);
        var report = reports.recorded(review);
        var usage = runtime.usage(workspaceId, id);
        var jobs = runtime.jobCounts(workspaceId, List.of(id)).getOrDefault(id, Map.of());
        return new ScanDetailDTO(
                summary,
                report.practices().stream()
                        .map(p -> new ScanPracticeRefDTO(
                                p.practiceId(),
                                p.practiceVersionId(),
                                p.number(),
                                p.practiceSlug(),
                                p.practiceName(),
                                p.reviewDigest()))
                        .toList(),
                Objects.requireNonNull(review.getBudgetUsd()).toPlainString(),
                usage.costUsd() == null ? null : usage.costUsd().toPlainString(),
                new ScanProgressDTO(
                        summary.counts().practiceItemPairs(),
                        summary.counts().reviewed() + summary.counts().withheld(),
                        Math.toIntExact(jobs.getOrDefault(ReviewJobStatus.RUNNING, 0L)),
                        Math.toIntExact(jobs.getOrDefault(ReviewJobStatus.QUEUED, 0L))));
    }

    @Transactional
    public ScanDetailDTO cancel(long workspaceId, UUID id) {
        reports.require(workspaceId, id);
        service.cancel(workspaceId, id, "CANCELLED_BY_ADMIN");
        return detail(workspaceId, id);
    }

    public ScanReportDTO report(long workspaceId, UUID id) {
        var review = reports.require(workspaceId, id);
        var recorded = reports.recorded(review);
        var expired = reports.expired(review);
        int withheld = expired ? 0 : reports.withheldProofPairs(review);
        return new ScanReportDTO(
                summary(review),
                review.getStatus() == ReviewStatus.COMPLETE,
                recorded.listingComplete(),
                recorded.baselineScanId(),
                recorded.comparison(),
                review.getStatus().isTerminal() ? recorded.recordedCounts() : null,
                reports.currentCounts(review),
                recorded.problemChanges(),
                recorded.practices().stream()
                        .map(p -> reports.currentPractice(review, p))
                        .toList(),
                recorded.withheldReasons(),
                recorded.notReviewedReasons(),
                recorded.removalGuard(),
                reports.exclusions(review),
                new ScanAvailabilityDTO(
                        expired,
                        review.getCompletedAt() == null
                                ? null
                                : review.getCompletedAt().plus(ScanReportService.RETENTION),
                        expired ? 0 : withheld + recorded.redactedProofPairs(),
                        expired
                                ? recorded.expiredProofPairs() == 0
                                        ? recorded.recordedCounts().reviewed()
                                        : recorded.expiredProofPairs()
                                : 0));
    }
}
