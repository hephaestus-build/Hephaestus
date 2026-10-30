package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.time.TimeBucketParams;
import de.tum.cit.aet.hephaestus.core.time.TimeBuckets;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.core.time.TimeRangeFilterParams;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewOverviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewQueueDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewTimingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/reviews")
@Tag(name = "Practice reviews")
@RequiredArgsConstructor
@Validated
@RequireAtLeastWorkspaceAdmin
public class ReviewSummaryController {

    static final String ALL_TIME_DESCRIPTION = "True counts all of the workspace's practice reviews: from the first"
            + " one's request, whatever from says, so the range may span more than 400 days. The response's from says"
            + " where it started.";

    private final ReviewSummaryQueryService queryService;
    private final ReviewOverviewService overviewService;
    private final ReviewTimingService timingService;
    private final ReviewQueueService queueService;
    private final Clock clock;
    private final ReviewService reviews;

    @PostMapping("/{reviewId}/cancel")
    @Operation(operationId = "cancelReview", summary = "Cancel a practice review")
    @AuditExempt(reason = "This operation changes execution state recorded on the review, not configuration or access")
    @ApiResponse(responseCode = "200", description = "Completed")
    @ApiResponse(responseCode = "202", description = "Accepted")
    public ResponseEntity<ReviewDTO> cancelReview(WorkspaceContext context, @PathVariable UUID reviewId) {
        reviews.cancel(context.id(), reviewId, "CANCELLED_BY_ADMIN");
        var review = queryService.view(context.id(), reviewId);
        return ResponseEntity.status(
                        review.status() == ReviewStatus.QUEUED || review.status() == ReviewStatus.RUNNING ? 202 : 200)
                .body(review);
    }

    @GetMapping
    @Operation(
            summary = "List practice reviews with result and feedback outcomes",
            description = "Reviews are ordered by when they were created, newest first unless sort is OLDEST."
                    + " queueState keeps the reviews whose job waits for a worker, is on hold for budget or runs"
                    + " now, as the queue counts them.",
            operationId = "listReviews")
    @ApiResponse(responseCode = "200", description = "Paginated review summaries returned")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PageResponseDTO<ReviewSummaryDTO>> listReviews(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @Valid @ParameterObject ReviewFilterParams filter) {
        // The native query orders the rows; the sort here keeps the page's sort metadata truthful.
        Sort.Direction direction = filter.sort() == ReviewListSort.OLDEST ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt", "id"));
        return ResponseEntity.ok(
                PageResponseDTO.from(queryService.list(workspaceContext.id(), filter.validated(), pageable)));
    }

    @GetMapping("/overview")
    @Operation(
            summary = "Count practice reviews, results and feedback in a time range",
            description = "In total, over time and by practice. Reviews count by when they were created,"
                    + " results by when they were recorded and feedback by when it was created, as the review,"
                    + " result and feedback lists filter them. The range is split into days, weeks or months,"
                    + " by its length, in the given time zone.",
            operationId = "getReviewOverview")
    @ApiResponse(responseCode = "200", description = "Practice reviews counted")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range or time zone",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewOverviewDTO> getPracticeReviewOverview(
            WorkspaceContext workspaceContext,
            @ParameterObject TimeBucketParams range,
            @Parameter(description = ALL_TIME_DESCRIPTION) @RequestParam(defaultValue = "false") boolean allTime) {
        TimeBuckets buckets = allTime
                ? range.toBuckets(overviewService.allTime(workspaceContext.id(), range.to()))
                : range.toBuckets(clock);
        return ResponseEntity.ok(overviewService.overview(workspaceContext.id(), buckets));
    }

    @GetMapping("/timing")
    @Operation(
            summary = "Measure how long each stage of the practice reviews in a time range took",
            description = "The median and 90th percentile of each stage, over the reviews created in the range as the"
                    + " overview counts them, and the reviews that need a look. Queue waits and budget holds are"
                    + " counted as they stand now, whenever the review was requested.",
            operationId = "getReviewTiming")
    @ApiResponse(responseCode = "200", description = "Practice reviews measured")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewTimingDTO> getPracticeReviewTiming(
            WorkspaceContext workspaceContext,
            @ParameterObject TimeRangeFilterParams range,
            @Parameter(description = ALL_TIME_DESCRIPTION) @RequestParam(defaultValue = "false") boolean allTime) {
        TimeRange measured =
                allTime ? overviewService.allTime(workspaceContext.id(), range.to()) : range.toRange(clock);
        return ResponseEntity.ok(timingService.timing(workspaceContext.id(), measured));
    }

    @GetMapping("/queue")
    @Operation(
            summary = "Show the practice review queue as it stands now",
            description = "How many practice reviews run, wait and are held for budget now, the oldest wait, the"
                    + " reviews at the head of the queue, and how many reviews finished in the last hour and the last"
                    + " 24 hours.",
            operationId = "getReviewQueue")
    @ApiResponse(responseCode = "200", description = "Practice review queue returned")
    public ResponseEntity<ReviewQueueDTO> getPracticeReviewQueue(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(queueService.queue(workspaceContext.id()));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "getReview", summary = "Get a review")
    public ReviewDetailDTO get(WorkspaceContext ws, @PathVariable UUID reviewId) {
        return queryService.detail(ws.id(), reviewId);
    }
}
