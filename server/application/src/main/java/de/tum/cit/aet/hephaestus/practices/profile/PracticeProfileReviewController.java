package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDetailDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practice-profile/reviews")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class PracticeProfileReviewController {
    private final PracticeProfileReviewService reviews;

    @GetMapping
    @Operation(operationId = "listOwnReviews", summary = "List reviews with results about you")
    public PageResponseDTO<ProfileReviewDTO> list(WorkspaceContext ws, @ParameterObject OwnReviewListParams query) {
        return PageResponseDTO.from(reviews.list(ws, query.filter(), query.pageable()));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "getOwnReview", summary = "Get your results from a review")
    public ProfileReviewDetailDTO get(WorkspaceContext ws, @PathVariable UUID reviewId) {
        return reviews.get(ws, reviewId);
    }
}
