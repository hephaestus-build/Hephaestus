package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorUserCounts;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorWorkQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Account identity translation stays on the connected SCM instance. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScmMentorWorkQuery implements MentorWorkQuery {
    private final GitProviderRegistry providers;
    private final WorkspaceActorSelector selector;
    private final MentorContextQueryRepository queries;

    /**
     * The workspace's connected instance and the account's provider actors on it.
     *
     * @param userIds the account's actors on the instance; empty when it has no active link there
     */
    public record Actors(long providerId, List<Long> userIds) {
        public Actors {
            userIds = List.copyOf(userIds);
        }
    }

    /** The account's actors on the connected instance; empty when the workspace connects no instance. */
    public Optional<Actors> actors(long workspaceId, long accountId) {
        return selector.connectedProviderId(workspaceId)
                .map(connected -> new Actors(
                        connected,
                        providers.accountActors(accountId).stream()
                                .filter(actor -> actor.providerId() == connected)
                                .map(GitProviderRegistry.ActorProfile::actorId)
                                .distinct()
                                .toList()));
    }

    /** The account's actors on the connected instance; none when the workspace connects no instance. */
    public List<Long> actorIds(long workspaceId, long accountId) {
        return actors(workspaceId, accountId).map(Actors::userIds).orElse(List.of());
    }

    @Override
    public MentorUserCounts fetchUserCounts(
            Long workspaceId, Long accountId, Instant twoWeeksAgo, Instant weekAgo, Instant now) {
        return actors(workspaceId, accountId)
                .filter(actors -> !actors.userIds().isEmpty())
                .map(actors -> queries.fetchUserCounts(
                        workspaceId, actors.providerId(), actors.userIds(), twoWeeksAgo, weekAgo, now))
                .orElseGet(() -> new MentorUserCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
    }

    @Override
    public List<AssignedIssue> findAssignedOpenIssues(Long workspaceId, Long accountId) {
        return actors(workspaceId, accountId)
                .filter(actors -> !actors.userIds().isEmpty())
                .map(actors ->
                        queries.findAssignedOpenIssues(workspaceId, actors.providerId(), actors.userIds()).stream()
                                .map(ScmMentorWorkQuery::assigned)
                                .toList())
                .orElse(List.of());
    }

    @Override
    public List<RequestedReview> findPendingReviewRequestPrs(Long workspaceId, Long accountId) {
        return actors(workspaceId, accountId)
                .filter(actors -> !actors.userIds().isEmpty())
                .map(actors -> queries.findPendingReviewRequestPrs(workspaceId, actors.providerId(), actors.userIds()))
                .orElse(List.of());
    }

    private static AssignedIssue assigned(Issue issue) {
        Repository repository = issue.getRepository();
        Milestone milestone = issue.getMilestone();
        return new AssignedIssue(
                issue.getId(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getHtmlUrl(),
                repository == null ? null : repository.getNameWithOwner(),
                milestone == null ? null : milestone.getTitle(),
                milestone == null ? null : milestone.getDueOn(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                issue.getLabels().stream().map(Label::getName).sorted().toList());
    }
}
