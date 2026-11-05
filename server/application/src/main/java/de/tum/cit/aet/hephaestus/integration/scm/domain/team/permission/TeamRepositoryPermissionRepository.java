package de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for team repository permission records.
 *
 * <p>Permissions are shared mirror rows with no {@code workspace_id} (ADR 0077). They are reached through their team.
 */
@Repository
public interface TeamRepositoryPermissionRepository
        extends JpaRepository<TeamRepositoryPermission, TeamRepositoryPermission.Id> {
    Optional<TeamRepositoryPermission> findByTeam_IdAndRepository_Id(Long teamId, Long repositoryId);

    /**
     * Find permission by team and repository IDs with the team eagerly loaded.
     * This is needed for authorization checks that access the team's organization.
     */
    @EntityGraph(attributePaths = {"team"})
    Optional<TeamRepositoryPermission> findWithTeamByTeam_IdAndRepository_Id(Long teamId, Long repositoryId);
}
