package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls.Page;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls.WorkAddress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.workspace.CurrentAccountUsers;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the address of a provider page into the reviewed work this workspace mirrored for it.
 *
 * <p>Every step is a fence, and the order matters. The provider and its server come from the workspace's
 * active connection — never from the hostname — and the page's origin must equal that server's exactly. The
 * repository is then found by provider instance <em>and</em> workspace monitor, because a name is unique
 * only per provider: the same GitLab namespace and project can exist on two servers. The number is looked up
 * per kind, because a GitLab issue #5 and merge request !5 coexist in one project. Nothing is dereferenced
 * and no provider credential is read; the answer comes only from what Hephaestus already holds.
 *
 * <p>Every way of not finding the work is the same 404 with the same sentence: absent, not monitored here,
 * another server or provider, deleted upstream, or withheld as confidential. Telling them apart would let a
 * member probe what another workspace mirrors, and a confidential issue's tombstone still carries the title
 * it had while public.
 */
@Service
@RequiredArgsConstructor
class ReviewContextService {

    static final String MALFORMED_ADDRESS = "The address is not an absolute HTTPS address of a provider page.";
    static final String UNSUPPORTED_ADDRESS = "The address is not the page of a pull request, merge request or issue.";
    static final String NO_WORK_AT_ADDRESS = "This workspace has no reviewed work at this address.";

    private final ConnectionService connections;
    private final List<ScmTokenSource> scmSources;
    private final ReviewableArtifactOwnershipRepository ownership;
    private final PullRequestRepository pullRequests;
    private final IssueRepository issues;
    private final CurrentAccountUsers currentAccountUsers;
    private final ReviewRequestAuthority authority;
    private final WorkspaceAccessService workspaceAccess;

    /** Read-only transaction: the requester check reads the work's author and assignees. */
    @Transactional(readOnly = true)
    public ReviewContextDTO resolve(long workspaceId, String url) {
        Page page = ReviewedWorkUrls.page(url).orElseThrow(() -> new IllegalArgumentException(MALFORMED_ADDRESS));
        IntegrationKind provider =
                connections.findActiveProviderKind(workspaceId).orElseThrow(ReviewContextService::noWork);
        // The server URL only; the same source's access token is never asked for.
        String serverUrl = scmSources.stream()
                .filter(source -> source.kind() == provider)
                .findFirst()
                .flatMap(source -> source.serverUrl(workspaceId))
                .orElseThrow(ReviewContextService::noWork);
        if (!ReviewedWorkUrls.configuredOrigin(serverUrl)
                .map(page.origin()::equals)
                .orElse(false)) {
            throw noWork();
        }
        WorkAddress address = ReviewedWorkUrls.workAddress(provider, page)
                .orElseThrow(() -> new IllegalArgumentException(UNSUPPORTED_ADDRESS));
        // The provider row is the one the connection names: its server URL and its kind.
        List<Repository> repositories =
                ownership.findMonitoredRepositories(workspaceId, serverUrl, address.repositoryName()).stream()
                        .filter(candidate -> candidate.getProvider().kind() == provider)
                        .toList();
        if (repositories.size() != 1) {
            throw noWork();
        }
        Repository repository = repositories.getFirst();
        Issue work = find(address, repository.getId())
                // A tombstone is also how a confidential issue is withheld; its row still has the public title.
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(ReviewContextService::noWork);

        var target = new ReviewRunTargetLookup.Target(
                address.kind(),
                work.getId(),
                provider,
                work.getNumber(),
                work.getTitle(),
                repository.getNameWithOwner(),
                null,
                work.getHtmlUrl());
        return new ReviewContextDTO(
                ReviewedWorkLabels.ref(address.kind(), work.getId(), target),
                mayRequestReview(workspaceId, work),
                workspaceAccess.isAdmin());
    }

    private Optional<Issue> find(WorkAddress address, long repositoryId) {
        if (ScmSignals.PULL_REQUEST.equals(address.kind())) {
            return pullRequests
                    .findByRepositoryIdAndNumber(repositoryId, address.number())
                    .map(Issue.class::cast);
        }
        return issues.findByRepositoryIdAndNumber(repositoryId, address.number());
    }

    /**
     * Standing comes from the caller's own linked identities, the same rule the request endpoint applies.
     * An instance admin's elevated entry into the workspace is not stored membership and grants none; a
     * read-only user view asks nothing.
     */
    private boolean mayRequestReview(long workspaceId, Issue work) {
        return UserViewContextHolder.get() == null
                && authority
                        .standingOf(workspaceId, work, currentAccountUsers.resolve())
                        .isPresent();
    }

    private static EntityNotFoundException noWork() {
        return new EntityNotFoundException(NO_WORK_AT_ADDRESS);
    }
}
