package de.tum.cit.aet.hephaestus.integration.scm.spi;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The SCM mirror rows that activity records and shows. Activity rows reference mirrored users and repositories, and
 * its read models render them, so these are the mirror's entities; only the SCM module writes them.
 */
public interface ActivityScmFacts {
    Optional<User> user(long userId);

    /** The mirrored users among {@code userIds}; an unknown id has no element. */
    List<User> users(Collection<Long> userIds);

    /** A reference for an activity row's actor, without a read. */
    User userReference(long userId);

    /** A reference for an activity row's repository, without a read. */
    Repository repositoryReference(long repositoryId);

    /** A member of the workspace by login, including one hidden from workspace activity. */
    Optional<User> member(long workspaceId, String login, long providerId);

    List<Team> organizationTeams(String organization, long providerId);

    /**
     * Who resolved a review thread, who opened its pull request, and in which repository; empty when the mirror has no
     * such thread.
     */
    Optional<ThreadFacts> reviewThread(long threadId);

    /**
     * @param repositoryId {@code null} when the thread's pull request or its repository is missing
     * @param resolvedById {@code null} when the mirror does not know who resolved the thread
     * @param pullRequestAuthorId {@code null} when the pull request or its author is missing
     */
    record ThreadFacts(
            @Nullable Long repositoryId,
            @Nullable Long resolvedById,
            @Nullable Long pullRequestAuthorId) {}
}
