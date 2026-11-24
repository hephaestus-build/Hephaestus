package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.review.dto.CreateReviewRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRequestOutcomeDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
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

@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices/review-requests")
@RequiredArgsConstructor
public class ReviewRequestController {
    private final ReviewRequestService requests;

    @PostMapping
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "requestReview", summary = "Ask for a review of an item")
    public ResponseEntity<ReviewRequestOutcomeDTO> requestReview(
            WorkspaceContext context, @Valid @RequestBody CreateReviewRequestDTO request) {
        return ResponseEntity.ok(requests.request(
                context.id(),
                Long.parseLong(request.itemId()),
                SecurityUtils.getCurrentAccountId().orElseThrow()));
    }
}
