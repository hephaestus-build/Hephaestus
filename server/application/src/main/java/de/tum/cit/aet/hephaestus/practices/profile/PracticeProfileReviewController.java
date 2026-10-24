package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practice-profile/reviews")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class PracticeProfileReviewController {
    private final PracticeProfileReviewService reviews;

    @GetMapping
    @Operation(operationId = "listOwnReviews", summary = "List reviews with results about you")
    public PageResponseDTO<ProfileReviewDTO> list(
            WorkspaceContext ws,
            @Parameter(description = "Only work of these kinds") @RequestParam(required = false) @Nullable
                    List<String> itemKind,
            @Parameter(description = "Only the reviews of this work, with the reviews of its pull request's reviews")
                    @RequestParam(required = false)
                    @Nullable
                    Long itemId,
            @Parameter(description = "Only reviews in these statuses") @RequestParam(required = false) @Nullable
                    List<ReviewStatus> status,
            @RequestParam(required = false) @Nullable Instant since,
            @Parameter(description = "OLDEST lists the oldest review first; NEWEST, the default, the newest")
                    @RequestParam(defaultValue = "NEWEST")
                    ReviewListOrder order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponseDTO.from(reviews.list(
                ws,
                itemKind,
                itemId,
                null,
                status,
                since,
                order == ReviewListOrder.OLDEST,
                PageRequest.of(page, size)));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "getOwnReview", summary = "Get your results from a review")
    public ProfileReviewDetailDTO get(WorkspaceContext ws, @PathVariable UUID reviewId) {
        return reviews.get(ws, reviewId);
    }
}
