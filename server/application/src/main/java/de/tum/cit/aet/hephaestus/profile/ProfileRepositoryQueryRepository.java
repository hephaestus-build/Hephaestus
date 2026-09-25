package de.tum.cit.aet.hephaestus.profile;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Workspace-scoped queries for repositories in the profile context.
 *
 * <p>This repository lives in the profile package because it joins integration.scm entities
 * with workspace entities (RepositoryToMonitor). The integration.scm package should not
 * depend on workspace entities to maintain clean architecture.
 *
 * <p>These queries are used for user profile display where workspace context is required.
 */
@org.springframework.stereotype.Repository
@WorkspaceAgnostic("Profile query helper joining with RepositoryToMonitor for workspace scoping")
public interface ProfileRepositoryQueryRepository extends JpaRepository<Repository, Long> {
    /**
     * Finds all repositories a user has contributed to within a workspace.
     *
     * @param userId the contributor's verified actor id
     * @param workspaceId the workspace to scope to
     * @return repositories the user has contributed to (via pull requests)
     */
    @Query("""
        SELECT DISTINCT r
        FROM Repository r
        JOIN PullRequest pr ON r.id = pr.repository.id
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE pr.author.id = :userId
            AND rtm.workspace.id = :workspaceId
        ORDER BY r.name ASC
        """)
    List<Repository> findContributedByUserId(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId);
}
