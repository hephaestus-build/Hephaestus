package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmWorkLookup {
    private final IssueRepository issues;
    private final PullRequestRepository pullRequests;

    Optional<Issue> find(ItemKind kind, long sourceId) {
        if (kind.equals(ScmItemEvents.PULL_REQUEST))
            return pullRequests.findByIdWithAuthorAndRepository(sourceId).map(Issue.class::cast);
        if (kind.equals(ScmItemEvents.ISSUE)) return issues.findByIdWithAuthorAndRepository(sourceId);
        return Optional.empty();
    }
}
