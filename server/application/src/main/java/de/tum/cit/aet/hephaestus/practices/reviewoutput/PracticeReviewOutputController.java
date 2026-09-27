package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalService;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.DecideFeedbackProposalRequestDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.FeedbackApprovalDTO;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationService;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewObservationDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.UpdateObservationValidityRequestDTO;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@RequestMapping("/practices/reviews")
@Tag(name = "Practice reviews")
@RequiredArgsConstructor
@Validated
@RequireAtLeastWorkspaceAdmin
public class PracticeReviewOutputController {

    private final ReviewObservationQueryService observationQueryService;
    private final ReviewFeedbackQueryService feedbackQueryService;
    private final FeedbackApprovalService feedbackApprovalService;
    private final ObservationInvalidationService invalidationService;

    @GetMapping("/observations")
    @Operation(
            summary = "List practice review observations across the workspace",
            description = "Results include linked feedback outcomes and are ordered newest first by default.",
            operationId = "listPracticeReviewObservations")
    @ApiResponse(responseCode = "200", description = "Paginated observations returned")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PagedModel<ReviewObservationDTO>> listObservations(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @Parameter(
                            description =
                                    "Sorting strategy. ACTIONABILITY orders problems from CRITICAL to INFO, then strengths, "
                                            + "then not-applicable observations; ties are newest first.")
                    @RequestParam(defaultValue = "NEWEST")
                    ReviewObservationSort sort,
            @Valid @ParameterObject ReviewObservationFilterParams filter) {
        return ResponseEntity.ok(new PagedModel<>(observationQueryService.list(
                workspaceContext.id(), filter.toFilter(), sort, PageRequest.of(page, size))));
    }

    @GetMapping("/observations/{observationId}")
    @Operation(
            summary = "Get an observation with its evidence and linked feedback",
            operationId = "getPracticeReviewObservation")
    @ApiResponse(
            responseCode = "200",
            description = "Observation detail returned",
            content = @Content(schema = @Schema(implementation = ReviewObservationDetailDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Observation not found in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewObservationDetailDTO> getObservation(
            WorkspaceContext workspaceContext, @PathVariable UUID observationId) {
        return ResponseEntity.ok(observationQueryService.get(workspaceContext.id(), observationId));
    }

    @PatchMapping("/observations/{observationId}/validity")
    @AuditExempt(reason = "The observation_invalidation row is the domain audit trail")
    @Operation(
            summary = "Invalidate an observation that was wrong when recorded, or restore it",
            description = "Invalidating stops feedback citing the observation that has not reached anyone yet. "
                    + "Feedback already delivered keeps its record, and a comment already posted on the "
                    + "provider stays there. Restoring does not re-send anything.",
            operationId = "updatePracticeReviewObservationValidity")
    @ApiResponse(
            responseCode = "200",
            description = "Observation detail after the change",
            content = @Content(schema = @Schema(implementation = ReviewObservationDetailDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Observation not found in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The observation is already in the requested state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewObservationDetailDTO> updateObservationValidity(
            WorkspaceContext workspaceContext,
            @PathVariable UUID observationId,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateObservationValidityRequestDTO request) {
        long actorAccountId = SecurityUtils.getCurrentAccountId().orElseThrow();
        invalidationService.setValidity(
                workspaceContext.id(), observationId, actorAccountId, request.valid(), request.reason());
        return ResponseEntity.ok(observationQueryService.get(workspaceContext.id(), observationId));
    }

    @GetMapping("/feedback")
    @Operation(
            summary = "List practice review feedback across the workspace",
            description = "Results are ordered newest first and include every delivery state.",
            operationId = "listPracticeReviewFeedback")
    @ApiResponse(responseCode = "200", description = "Paginated feedback returned")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter or pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PagedModel<ReviewFeedbackDTO>> listFeedback(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @Valid @ParameterObject ReviewFeedbackFilterParams filter) {
        return ResponseEntity.ok(new PagedModel<>(
                feedbackQueryService.list(workspaceContext.id(), filter.toFilter(), PageRequest.of(page, size))));
    }

    @GetMapping("/feedback/{feedbackId}")
    @Operation(
            summary = "Get feedback with its stored body, observations and placements",
            operationId = "getPracticeReviewFeedback")
    @ApiResponse(
            responseCode = "200",
            description = "Feedback detail returned",
            content = @Content(schema = @Schema(implementation = ReviewFeedbackDetailDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Feedback not found in this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewFeedbackDetailDTO> getFeedback(
            WorkspaceContext workspaceContext, @PathVariable UUID feedbackId) {
        return ResponseEntity.ok(feedbackQueryService.get(workspaceContext.id(), feedbackId));
    }

    @PutMapping("/feedback/{feedbackId}/approval")
    @AuditExempt(reason = "The immutable feedback_approval row is the domain audit trail")
    @Operation(summary = "Approve or reject an immutable feedback proposal", operationId = "decideFeedbackProposal")
    public ResponseEntity<FeedbackApprovalDTO> decideFeedbackProposal(
            WorkspaceContext workspaceContext,
            @PathVariable UUID feedbackId,
            @Valid @org.springframework.web.bind.annotation.RequestBody DecideFeedbackProposalRequestDTO request) {
        long actorAccountId = SecurityUtils.getCurrentAccountId().orElseThrow();
        return ResponseEntity.ok(FeedbackApprovalDTO.from(
                feedbackApprovalService.decide(workspaceContext.id(), feedbackId, actorAccountId, request)));
    }

    @GetMapping("/feedback/{feedbackId}/approval")
    @Operation(summary = "Get the decision for a feedback proposal", operationId = "getFeedbackProposalDecision")
    public ResponseEntity<FeedbackApprovalDTO> getFeedbackProposalDecision(
            WorkspaceContext workspaceContext, @PathVariable UUID feedbackId) {
        return ResponseEntity.ok(
                FeedbackApprovalDTO.from(feedbackApprovalService.get(workspaceContext.id(), feedbackId)));
    }
}
