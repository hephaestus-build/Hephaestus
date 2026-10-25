package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.profile.OwnReviewFilter;
import de.tum.cit.aet.hephaestus.practices.profile.PracticeProfileReviewService;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileReviewDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PracticeGroupReviewService {
    private final PracticeGroupService groups;
    private final PracticeProfileReviewService reviews;

    public Page<ProfileReviewDTO> list(
            WorkspaceContext ws, String groupSlug, @Nullable String itemKind, @Nullable Instant since, Pageable page) {
        groups.getGroup(ws, groupSlug);
        return reviews.list(
                ws,
                new OwnReviewFilter(itemKind == null ? null : List.of(itemKind), null, groupSlug, null, since, false),
                page);
    }
}
