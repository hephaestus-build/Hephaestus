package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practice-groups/{groupSlug}")
@Tag(name = "Practice Group Detail", description = "Practice group trends and review runs for the current developer")
@RequiredArgsConstructor
@Validated
public class PracticeGroupDetailController {

    private final PracticeGroupReviewRunService reviewRunService;
    private final PracticeGroupTrendQueryService trendQueryService;

    @GetMapping("/trend")
    @Operation(
            operationId = "getPracticeGroupTrend",
            summary = "Get the evidence-supported trend for a practice group",
            description = "Returns the group direction and every eligible practice direction with inspectable support.")
    @ApiResponse(responseCode = "200", description = "Practice-group trend returned")
    @ApiResponse(
            responseCode = "404",
            description = "Practice group not found",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<PracticeGroupTrendDTO> getTrend(
            WorkspaceContext workspaceContext, @PathVariable String groupSlug) {
        return ResponseEntity.ok(trendQueryService.get(workspaceContext, groupSlug));
    }

    @GetMapping("/review-runs")
    @Operation(
            operationId = "listPracticeGroupReviewRuns",
            summary = "List complete review runs for a practice group",
            description = "Returns complete review runs newest first, including visible undecided observations.")
    @ApiResponse(responseCode = "200", description = "Paginated review runs returned")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Practice group not found",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<PracticeGroupReviewRunsPageDTO> listReviewRuns(
            WorkspaceContext workspaceContext,
            @PathVariable String groupSlug,
            @Valid @ParameterObject PracticeGroupReviewRunFilterParams filter) {
        return ResponseEntity.ok(
                reviewRunService.list(workspaceContext, groupSlug, filter.runFilters(), filter.pageable()));
    }
}
