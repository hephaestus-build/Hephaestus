package de.tum.cit.aet.hephaestus.integration.scm.activity;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmMemberRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ActivityScmFactsAdapter implements ActivityScmFacts {
    private final UserRepository users;
    private final RepositoryRepository repositories;
    private final ScmMemberRepository members;
    private final TeamRepository teams;
    private final PullRequestReviewThreadRepository threads;

    @Override
    public Optional<User> user(long userId) {
        return users.findById(userId);
    }

    @Override
    public List<User> users(Collection<Long> userIds) {
        return users.findAllById(userIds);
    }

    @Override
    public User userReference(long userId) {
        return users.getReferenceById(userId);
    }

    @Override
    public Repository repositoryReference(long repositoryId) {
        return repositories.getReferenceById(repositoryId);
    }

    @Override
    public Optional<User> member(long workspaceId, String login, long providerId) {
        return members.findMemberByLogin(workspaceId, login, providerId);
    }

    @Override
    public List<Team> organizationTeams(String organization, long providerId) {
        return teams.findAllByOrganizationIgnoreCaseAndProviderId(organization, providerId);
    }

    @Override
    public Optional<ThreadFacts> reviewThread(long threadId) {
        return threads.findById(threadId).map(thread -> {
            var pullRequest = thread.getPullRequest();
            var resolvedBy = thread.getResolvedBy();
            var author = pullRequest == null ? null : pullRequest.getAuthor();
            return new ThreadFacts(
                    pullRequest == null || pullRequest.getRepository() == null
                            ? null
                            : pullRequest.getRepository().getId(),
                    resolvedBy == null ? null : resolvedBy.getId(),
                    author == null ? null : author.getId());
        });
    }
}
