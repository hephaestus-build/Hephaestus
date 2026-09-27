package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemListDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What is open for one member right now. */
@Service
@RequiredArgsConstructor
public class OpenWorkService {

    /** Enough to see what is waiting; the provider's own lists are one link away. */
    static final int LIMIT = 50;

    private final WorkItemQueryRepository workItems;
    private final ActivityScopeResolver scopes;

    @Transactional(readOnly = true)
    public OpenWorkDTO openWork(long workspaceId, String login) {
        long userId = scopes.member(workspaceId, login).getId();
        Pageable first = PageRequest.ofSize(LIMIT);
        return new OpenWorkDTO(
                list(workItems.findReviewRequests(workspaceId, userId, first)),
                list(workItems.findOpenPullRequests(workspaceId, userId, first)),
                list(workItems.findAssignedIssues(workspaceId, userId, first)));
    }

    private static WorkItemListDTO list(Slice<? extends Issue> work) {
        return new WorkItemListDTO(
                work.getContent().stream().map(WorkItemDTO::from).toList(), work.hasNext());
    }
}
