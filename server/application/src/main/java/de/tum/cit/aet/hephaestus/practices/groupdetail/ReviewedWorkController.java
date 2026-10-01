package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reader's own reviews of one piece of work. Feedback posted on a pull request, merge request or issue links
 * here, so the developer it is addressed to can answer it; anybody else finds nothing of theirs.
 */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practices/reviewed-work/{artifactKind}/{artifactId}")
@Tag(name = "Practice Group Detail", description = "Practice group trends and review runs for the current developer")
@RequiredArgsConstructor
@Validated
public class ReviewedWorkController {

    private final PracticeGroupReviewRunService reviewRunService;

    @GetMapping("/review-runs")
    @Operation(
            operationId = "listReviewedWorkReviewRuns",
            summary = "List the current developer's review runs of one piece of work",
            description = "Complete review runs newest first, every practice group together, with each observation's"
                    + " feedback response.")
    @ApiResponse(responseCode = "200", description = "Paginated review runs returned")
    @ApiResponse(
            responseCode = "400",
            description = "Unknown kind of work or invalid pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PracticeGroupReviewRunsPageDTO> listReviewRuns(
            WorkspaceContext workspaceContext,
            @Parameter(description = "Kind of reviewed work, e.g. scm.pull_request") @PathVariable String artifactKind,
            @PathVariable long artifactId,
            @Parameter(description = "Zero-based page, at most 100")
                    @RequestParam(required = false)
                    @PositiveOrZero
                    @Max(100)
                    @Nullable
                    Integer page,
            @Parameter(description = "Page size from 1 to 50")
                    @RequestParam(required = false)
                    @Min(1)
                    @Max(50)
                    @Nullable
                    Integer size) {
        ArtifactKind kind = Objects.requireNonNull(QueryFilterSupport.artifactKind(artifactKind));
        return ResponseEntity.ok(reviewRunService.listForWork(
                workspaceContext, kind, artifactId, QueryFilterSupport.pageable(page, size, 10)));
    }
}
