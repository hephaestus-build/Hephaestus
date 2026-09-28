package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.time.TimeBucketParams;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
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
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@RequestMapping("/practices/reviews")
@Tag(name = "Practice reviews")
@RequiredArgsConstructor
@Validated
@RequireAtLeastWorkspaceAdmin
public class PracticeReviewSummaryController {

    private final ReviewRunSummaryQueryService queryService;
    private final PracticeEvidenceOutcomeService evidenceOutcomeService;
    private final PracticeReviewOverviewService overviewService;
    private final Clock clock;

    @GetMapping
    @Operation(
            summary = "List practice reviews with observation and feedback outcomes",
            description = "Results are ordered newest first.",
            operationId = "listPracticeReviews")
    @ApiResponse(responseCode = "200", description = "Paginated review summaries returned")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PagedModel<ReviewRunSummaryDTO>> listReviews(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Valid @ParameterObject ReviewRunFilterParams filter) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return ResponseEntity.ok(
                new PagedModel<>(queryService.list(workspaceContext.id(), filter.validated(), pageable)));
    }

    @GetMapping("/evidence-outcomes")
    @Operation(
            summary = "Summarize how each practice's evidence requirements turned out on recent reviews",
            description = "One entry per practice that recent reviews considered, with the sources that skipped it.",
            operationId = "listPracticeEvidenceOutcomes")
    @ApiResponse(responseCode = "200", description = "Evidence outcomes returned")
    public ResponseEntity<List<PracticeEvidenceOutcomeDTO>> listEvidenceOutcomes(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(evidenceOutcomeService.recentOutcomes(workspaceContext.id()));
    }

    @GetMapping("/overview")
    @Operation(
            summary = "Count practice reviews, observations and feedback in a time range",
            description = "In total, over time and by practice. Reviews count by when they were created,"
                    + " observations by when they were recorded and feedback by when it was created, as the review,"
                    + " observation and feedback lists filter them. The range is split into days, weeks or months,"
                    + " by its length, in the given time zone.",
            operationId = "getPracticeReviewOverview")
    @ApiResponse(responseCode = "200", description = "Practice reviews counted")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range or time zone",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PracticeReviewOverviewDTO> getPracticeReviewOverview(
            WorkspaceContext workspaceContext, @ParameterObject TimeBucketParams range) {
        return ResponseEntity.ok(overviewService.overview(workspaceContext.id(), range.toBuckets(clock)));
    }
}
