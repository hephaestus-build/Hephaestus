package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@TeamOnly
@RequestMapping("/practices/reviews")
@Tag(name = "Practice reviews")
@RequiredArgsConstructor
@Validated
@RequireAtLeastWorkspaceAdmin
public class ReviewSummaryController {

    private final ReviewSummaryQueryService queryService;
    private final ReviewService reviews;

    @PostMapping("/{reviewId}/cancel")
    @Operation(operationId = "cancelReview", summary = "Cancel a practice review")
    @AuditExempt(reason = "This operation changes execution state recorded on the review, not configuration or access")
    @ApiResponse(responseCode = "200", description = "Completed")
    @ApiResponse(responseCode = "202", description = "Accepted")
    public ResponseEntity<ReviewDTO> cancelReview(WorkspaceContext context, @PathVariable UUID reviewId) {
        reviews.cancel(context.id(), reviewId);
        var review = queryService.view(context.id(), reviewId);
        return ResponseEntity.status(
                        review.status() == ReviewStatus.QUEUED || review.status() == ReviewStatus.RUNNING ? 202 : 200)
                .body(review);
    }

    @GetMapping
    @Operation(
            summary = "List practice reviews with result and feedback outcomes",
            description = "Reviews are ordered by when they were created, newest first unless sort is OLDEST."
                    + " queueState keeps the reviews whose job waits for a server, is on hold for budget or runs"
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
    public ResponseEntity<OffsetPageDTO<ReviewSummaryDTO>> listReviews(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @Valid @ParameterObject ReviewFilterParams filter) {
        // The native query orders the rows; the sort here keeps the page's sort metadata truthful.
        Sort.Direction direction = filter.sort() == ReviewListSort.OLDEST ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt", "id"));
        return ResponseEntity.ok(
                OffsetPageDTO.from(queryService.list(workspaceContext.id(), filter.validated(), pageable)));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "getReview", summary = "Get a review")
    public ReviewDetailDTO get(WorkspaceContext ws, @PathVariable UUID reviewId) {
        return queryService.detail(ws.id(), reviewId);
    }
}
