package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
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

@WorkspaceScopedController
@RequestMapping("/product-feedback")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class FeedbackController {
    private final FeedbackService service;

    @GetMapping("/surveys")
    @Operation(
            operationId = "listAvailableProductSurveys",
            summary = "List surveys not yet handled by the current account")
    public List<SurveyDTO> availableSurveys(WorkspaceContext workspace) {
        return service.available(workspace.id(), CurrentAccount.requireId());
    }

    @PostMapping("/surveys/{surveyId}/responses")
    @Operation(operationId = "submitProductSurveyResponse", summary = "Submit a survey response once")
    public ResponseEntity<Void> submit(
            WorkspaceContext workspace, @PathVariable UUID surveyId, @Valid @RequestBody SubmitSurveyDTO request) {
        service.submit(surveyId, workspace.id(), CurrentAccount.requireId(), request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/surveys/{surveyId}/dismissal")
    @Operation(operationId = "dismissProductSurvey", summary = "Dismiss a survey for the current account")
    public ResponseEntity<Void> dismiss(WorkspaceContext workspace, @PathVariable UUID surveyId) {
        service.dismiss(surveyId, workspace.id(), CurrentAccount.requireId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    @Operation(
            operationId = "submitWorkspaceProductFeedback",
            summary = "Send product feedback to instance administrators")
    public ResponseEntity<Void> feedback(WorkspaceContext workspace, @Valid @RequestBody FeedbackRequestDTO request) {
        service.addFeedback(request, CurrentAccount.requireId(), workspace.id());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/surveys/{surveyId}/dismissal")
    @Operation(
            operationId = "restoreProductSurvey",
            summary = "Undo a survey dismissal without changing a submitted response")
    public ResponseEntity<Void> restore(WorkspaceContext workspace, @PathVariable UUID surveyId) {
        service.restore(surveyId, workspace.id(), CurrentAccount.requireId());
        return ResponseEntity.noContent().build();
    }
}
