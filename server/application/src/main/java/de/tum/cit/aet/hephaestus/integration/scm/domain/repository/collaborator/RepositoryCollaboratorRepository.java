package de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for repository collaborator records.
 *
 * <p>All queries filter by repository ID which inherently carries scope
 * through the Repository -> Organization relationship chain.
 */
@WorkspaceAgnostic("Collaborators scoped through repository_id -> repository.workspace_id")
public interface RepositoryCollaboratorRepository
        extends JpaRepository<RepositoryCollaborator, RepositoryCollaborator.Id> {
    @Query("SELECT c FROM RepositoryCollaborator c WHERE c.repository.id = :repositoryId AND c.user.id = :userId")
    Optional<RepositoryCollaborator> findByRepositoryIdAndUserId(
            @Param("repositoryId") Long repositoryId, @Param("userId") Long userId);

    List<RepositoryCollaborator> findByRepository_Id(Long repositoryId);

    @Query("""
            SELECT DISTINCT c FROM RepositoryCollaborator c
            JOIN FETCH c.user
            WHERE c.repository.organization.login = :orgLogin
            AND c.repository.provider.id = :providerId
            AND c.permission IN :permissions
        """)
    List<RepositoryCollaborator> findByOrgLoginAndPermissions(
            @Param("orgLogin") String orgLogin,
            @Param("providerId") Long providerId,
            @Param("permissions") List<RepositoryCollaborator.Permission> permissions);
}
