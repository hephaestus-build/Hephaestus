package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Instance-level GitHub App installations that an instance admin decides before a workspace exists")
public interface GitHubInstallationRequestRepository extends JpaRepository<GitHubInstallationRequest, Long> {

    /** Records an installation once. A redelivered or replayed installation event changes nothing. */
    @Modifying
    @Query(value = """
            INSERT INTO github_installation_request
                (installation_id, account_id, account_login, account_kind, status, received_at)
            VALUES (:installationId, :accountId, :accountLogin, :accountKind, 'PENDING', now())
            ON CONFLICT (installation_id) DO NOTHING
            """, nativeQuery = true)
    int recordPending(
            @Param("installationId") long installationId,
            @Param("accountId") @Nullable Long accountId,
            @Param("accountLogin") String accountLogin,
            @Param("accountKind") String accountKind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GitHubInstallationRequest r WHERE r.installationId = :installationId")
    Optional<GitHubInstallationRequest> findForUpdate(@Param("installationId") long installationId);

    @Query("SELECT r.status FROM GitHubInstallationRequest r WHERE r.installationId = :installationId")
    Optional<GitHubInstallationRequestStatus> findStatus(@Param("installationId") long installationId);

    /** An adopted installation keeps its record: it is what stops a replayed event from creating a workspace. */
    @Modifying
    @Query("""
            DELETE FROM GitHubInstallationRequest r
            WHERE r.installationId = :installationId
              AND r.status <> de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequestStatus.ADOPTED
            """)
    int deleteUnadopted(@Param("installationId") long installationId);

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequestDTO(
                r.installationId, r.accountLogin, r.accountKind, r.status, r.receivedAt, r.decidedAt, w.workspaceSlug)
            FROM GitHubInstallationRequest r
            LEFT JOIN Workspace w ON w.id = r.workspaceId
            ORDER BY r.receivedAt DESC, r.installationId DESC
            """)
    List<GitHubInstallationRequestDTO> findAllViews();

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequestDTO(
                r.installationId, r.accountLogin, r.accountKind, r.status, r.receivedAt, r.decidedAt, w.workspaceSlug)
            FROM GitHubInstallationRequest r
            LEFT JOIN Workspace w ON w.id = r.workspaceId
            WHERE r.installationId = :installationId
            """)
    Optional<GitHubInstallationRequestDTO> findView(@Param("installationId") long installationId);

    /** The workspace purge keeps the workspace row, so its foreign key alone would never clear the pointer. */
    @Modifying
    @Query("UPDATE GitHubInstallationRequest r SET r.workspaceId = NULL WHERE r.workspaceId = :workspaceId")
    int clearWorkspace(@Param("workspaceId") long workspaceId);
}
