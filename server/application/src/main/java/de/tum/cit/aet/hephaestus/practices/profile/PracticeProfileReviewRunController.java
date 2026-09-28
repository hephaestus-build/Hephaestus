package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunDetailDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Every review run on the calling developer's own work, across every practice group. Like the overview it
 * sits beside, the developer is resolved from the caller and there is no user parameter on this route.
 */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practice-profile/review-runs")
@Tag(name = "Practice Profile", description = "The current developer's practice profile")
@RequiredArgsConstructor
public class PracticeProfileReviewRunController {

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final PracticeProfileReviewRunService reviewRunService;

    @GetMapping
    @Operation(
            operationId = "listPracticeProfileReviewRuns",
            summary = "Every review run on the developer's own work, newest first",
            description = "One row per run that recorded an observation about the calling developer, across"
                    + " every practice group, with what that run found about them. Counts are narrowed to"
                    + " this developer: a run over shared work says nothing here about anybody else."
                    + " kind narrows the list to one kind of work; since keeps only the runs reviewed after"
                    + " that moment.")
    @ApiResponse(responseCode = "200", description = "Review runs returned")
    public ResponseEntity<ProfileReviewRunsPageDTO> listReviewRuns(
            WorkspaceContext workspaceContext,
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
                    Integer size,
            @Parameter(description = "Restrict to one kind of work, e.g. scm.pull_request")
                    @RequestParam(required = false)
                    @Nullable
                    String kind,
            @Parameter(description = "Keep only runs reviewed after this moment; the bound is exclusive")
                    @RequestParam(required = false)
                    @Nullable
                    Instant since) {
        return ResponseEntity.ok(reviewRunService.list(
                workspaceContext,
                QueryFilterSupport.artifactKind(kind),
                since,
                QueryFilterSupport.pageable(page, size, DEFAULT_PAGE_SIZE)));
    }

    @GetMapping("/{reviewId}")
    @Operation(
            operationId = "getPracticeProfileReviewRun",
            summary = "One review run on the developer's own work, with what it observed about them",
            description = "A run that recorded nothing about the calling developer is not theirs to read and"
                    + " answers 404, the same answer a run in another workspace gets.")
    @ApiResponse(responseCode = "200", description = "Review run returned")
    public ResponseEntity<ProfileReviewRunDetailDTO> getReviewRun(
            WorkspaceContext workspaceContext, @PathVariable UUID reviewId) {
        return ResponseEntity.ok(reviewRunService.getRun(workspaceContext, reviewId));
    }
}
