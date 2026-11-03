package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
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
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practice-groups/{groupSlug}/reviews")
@RequiredArgsConstructor
public class PracticeGroupDetailController {
    private final PracticeGroupReviewService reviews;

    @GetMapping
    @Operation(operationId = "listOwnGroupReviews", summary = "List your reviews in a practice group")
    public OffsetPageDTO<ProfileReviewDTO> list(
            WorkspaceContext ws,
            @PathVariable String groupSlug,
            @RequestParam(required = false) @Nullable String itemKind,
            @RequestParam(required = false) @Nullable Instant since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OffsetPageDTO.from(reviews.list(ws, groupSlug, itemKind, since, PageRequest.of(page, size)));
    }
}
