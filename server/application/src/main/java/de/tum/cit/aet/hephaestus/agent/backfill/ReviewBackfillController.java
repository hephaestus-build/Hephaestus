package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.agent.backfill.dto.CreateReviewBackfillRunRequestDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.ReviewBackfillRunDTO;
import de.tum.cit.aet.hephaestus.agent.backfill.dto.UpdateReviewBackfillRunStatusRequestDTO;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Review-backfill campaigns.
 *
 * <p>Creating a run costs nothing: it enumerates the scope and prices it, and returns a run awaiting
 * confirmation. The spend begins only at {@code PATCH .../status} with {@code RUNNING}, which is a
 * separate, separately-audited act by a named admin.
 */
@WorkspaceScopedController
@RequestMapping("/practices/backfill-runs")
@Tag(name = "Practice Review Backfill", description = "Bounded campaigns that review work which already existed")
@RequiredArgsConstructor
@Validated
public class ReviewBackfillController {

    private final ReviewBackfillService reviewBackfillService;

    @PostMapping
    @Operation(
            summary = "Enumerate and price a backfill campaign",
            description = "Creates a run awaiting confirmation. Submits nothing and spends nothing.")
    @ApiResponse(
            responseCode = "201",
            description = "Scope enumerated and priced",
            content = @Content(schema = @Schema(implementation = ReviewBackfillRunDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The window is inverted, too long, or covers too many artifacts",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "A backfill is already active for this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_BACKFILL_RUN")
    public ResponseEntity<ReviewBackfillRunDTO> preflightBackfillRun(
            WorkspaceContext workspaceContext, @Valid @RequestBody CreateReviewBackfillRunRequestDTO request) {
        ReviewBackfillRunDTO run = reviewBackfillService.preflight(workspaceContext, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{runId}")
                .buildAndExpand(run.id())
                .toUri();
        return ResponseEntity.created(location).body(run);
    }

    @GetMapping
    @Operation(summary = "List this workspace's recent backfill campaigns")
    @ApiResponse(responseCode = "200", description = "Campaigns returned")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<ReviewBackfillRunDTO>> listBackfillRuns(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(reviewBackfillService.list(workspaceContext));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get one backfill campaign, including its live progress")
    @ApiResponse(
            responseCode = "200",
            description = "Campaign returned",
            content = @Content(schema = @Schema(implementation = ReviewBackfillRunDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such campaign in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<ReviewBackfillRunDTO> getBackfillRun(
            WorkspaceContext workspaceContext, @PathVariable UUID runId) {
        return ResponseEntity.ok(reviewBackfillService.get(workspaceContext, runId));
    }

    @PatchMapping("/{runId}/status")
    @Operation(
            summary = "Confirm or cancel a backfill campaign",
            description =
                    "RUNNING authorises the estimated spend and starts the campaign; CANCELLED stops it for good.")
    @ApiResponse(
            responseCode = "200",
            description = "Campaign updated",
            content = @Content(schema = @Schema(implementation = ReviewBackfillRunDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The campaign cannot make that transition from its current state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "REVIEW_BACKFILL_RUN")
    public ResponseEntity<ReviewBackfillRunDTO> updateBackfillRunStatus(
            WorkspaceContext workspaceContext,
            @PathVariable UUID runId,
            @Valid @RequestBody UpdateReviewBackfillRunStatusRequestDTO request) {
        return ResponseEntity.ok(reviewBackfillService.updateStatus(workspaceContext, runId, request.status()));
    }
}
