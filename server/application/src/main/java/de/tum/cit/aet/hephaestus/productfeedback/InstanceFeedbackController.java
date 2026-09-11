package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequestMapping("/product-feedback")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@WorkspaceAgnostic("Feedback submitted without a workspace belongs to the instance")
public class InstanceFeedbackController {
    private final FeedbackService service;

    @PostMapping
    @Operation(operationId = "submitInstanceProductFeedback", summary = "Send instance-scoped product feedback")
    public ResponseEntity<Void> feedback(@Valid @RequestBody FeedbackRequestDTO request) {
        service.add(request, CurrentAccount.requireId(), null);
        return ResponseEntity.accepted().build();
    }
}
