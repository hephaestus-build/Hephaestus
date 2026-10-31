package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/product-feedback")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class FeedbackController {
    private final FeedbackService feedback;

    @PostMapping
    @Operation(
            operationId = "submitWorkspaceProductFeedback",
            summary = "Send product feedback to instance administrators")
    public ResponseEntity<Void> feedback(WorkspaceContext workspace, @Valid @RequestBody FeedbackRequestDTO request) {
        feedback.add(request, CurrentAccount.requireId(), workspace.id());
        return ResponseEntity.accepted().build();
    }
}
