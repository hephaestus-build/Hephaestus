package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResolutionCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** One recipient-facing API for assessing and resolving delivered feedback. */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practices/feedback")
@Tag(name = "Feedback Response", description = "Recipient responses to delivered practice feedback")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class FeedbackResponseController {

    private final FeedbackResponseService responseService;

    @PutMapping("/{feedbackId}/response")
    @Operation(
            operationId = "replaceOwnFeedbackResponse",
            summary = "Replace the response to delivered feedback",
            description = "Sets the complete current response. Repeating the same request has no effect.")
    @ApiResponse(responseCode = "200", description = "Response replaced")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid response",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "404",
            description = "Delivered feedback not found for the current recipient",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "A workspace admin withdrew this feedback",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<FeedbackResponseDTO> replace(
            WorkspaceContext workspaceContext,
            @PathVariable UUID feedbackId,
            @Valid @RequestBody FeedbackResponseRequestDTO request) {
        return ResponseEntity.ok(responseService.replaceResponse(workspaceContext, feedbackId, request));
    }

    @GetMapping("/{feedbackId}/response")
    @Operation(operationId = "getOwnFeedbackResponse", summary = "Get the current feedback response")
    @ApiResponse(responseCode = "200", description = "Complete current response returned")
    @ApiResponse(
            responseCode = "404",
            description = "Delivered feedback not found for the current recipient",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<FeedbackResponseDTO> current(
            WorkspaceContext workspaceContext, @PathVariable UUID feedbackId) {
        return ResponseEntity.ok(responseService.getResponse(workspaceContext, feedbackId));
    }

    @RecentSignInExempt(reason = "takes back the developer's own answer, which they can give again")
    @DeleteMapping("/{feedbackId}/response")
    @Operation(operationId = "clearOwnFeedbackResponse", summary = "Clear the response to delivered feedback")
    @ApiResponse(responseCode = "200", description = "Complete cleared response returned")
    @ApiResponse(
            responseCode = "404",
            description = "Delivered feedback not found for the current recipient",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<FeedbackResponseDTO> delete(
            WorkspaceContext workspaceContext, @PathVariable UUID feedbackId) {
        return ResponseEntity.ok(responseService.deleteResponse(workspaceContext, feedbackId));
    }

    @GetMapping("/resolution-counts")
    @Operation(operationId = "getOwnFeedbackResolutionCounts", summary = "Get feedback resolution counts")
    public ResponseEntity<FeedbackResolutionCountsDTO> resolutionCounts(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(responseService.getResolutionCounts(workspaceContext));
    }
}
