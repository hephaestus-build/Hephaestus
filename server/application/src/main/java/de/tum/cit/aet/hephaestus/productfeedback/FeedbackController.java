package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyInvitationDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** What a workspace member can do: read their survey invitations, act on one, and send feedback. */
@WorkspaceScopedController
@RequestMapping("/product-feedback")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class FeedbackController {
    private final SurveyService surveys;
    private final FeedbackService feedback;

    @GetMapping("/surveys")
    @Operation(
            operationId = "listProductSurveyInvitations",
            summary = "List the open surveys the current account has neither answered nor declined")
    public List<SurveyInvitationDTO> invitations(WorkspaceContext workspace) {
        return surveys.invitations(workspace.id(), CurrentAccount.requireId());
    }

    @PutMapping("/surveys/{surveyId}/invitation")
    @Operation(
            operationId = "acknowledgeProductSurveyInvitation",
            summary = "Record that the invitation was shown to the current account")
    public ResponseEntity<Void> acknowledge(WorkspaceContext workspace, @PathVariable UUID surveyId) {
        surveys.markInvited(surveyId, workspace.id(), CurrentAccount.requireId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/surveys/{surveyId}/responses")
    @Operation(operationId = "submitProductSurveyResponse", summary = "Submit a survey response once")
    public ResponseEntity<Void> respond(
            WorkspaceContext workspace, @PathVariable UUID surveyId, @Valid @RequestBody SubmitSurveyDTO request) {
        surveys.respond(surveyId, workspace.id(), CurrentAccount.requireId(), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/surveys/{surveyId}/dismissal")
    @Operation(operationId = "dismissProductSurvey", summary = "Decline a survey for the current account")
    public ResponseEntity<Void> decline(WorkspaceContext workspace, @PathVariable UUID surveyId) {
        surveys.decline(surveyId, workspace.id(), CurrentAccount.requireId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/surveys/{surveyId}/dismissal")
    @Operation(operationId = "restoreProductSurvey", summary = "Undo a decline without touching a submitted response")
    public ResponseEntity<Void> restore(WorkspaceContext workspace, @PathVariable UUID surveyId) {
        surveys.restore(surveyId, workspace.id(), CurrentAccount.requireId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    @Operation(
            operationId = "submitWorkspaceProductFeedback",
            summary = "Send product feedback to instance administrators")
    public ResponseEntity<Void> feedback(WorkspaceContext workspace, @Valid @RequestBody FeedbackRequestDTO request) {
        feedback.add(request, CurrentAccount.requireId(), workspace.id());
        return ResponseEntity.accepted().build();
    }
}
