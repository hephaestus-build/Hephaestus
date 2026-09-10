package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkspaceAccessRequestRepository extends JpaRepository<WorkspaceAccessRequest, Long> {
    Optional<WorkspaceAccessRequest> findByIdAndWorkspace_Id(Long id, Long workspaceId);

    List<WorkspaceAccessRequest> findByWorkspace_IdAndAccountIdOrderBySubmittedAtDescIdDesc(
            Long workspaceId, Long accountId, Pageable page);

    List<WorkspaceAccessRequest> findByWorkspace_IdAndStatusOrderBySubmittedAtAscIdAsc(
            Long workspaceId, WorkspaceAccessRequest.Status status, Pageable page);

    boolean existsByWorkspace_IdAndAccountIdAndStatusIn(
            Long workspaceId, Long accountId, Collection<WorkspaceAccessRequest.Status> statuses);

    @WorkspaceAgnostic("Account erasure locates only workspace IDs; deletion then locks and scopes each workspace")
    @Query(
            "SELECT DISTINCT r.workspace.id FROM WorkspaceAccessRequest r WHERE r.accountId = :accountId OR r.decidedByAccountId = :accountId ORDER BY r.workspace.id")
    List<Long> findWorkspaceIdsForErasure(@Param("accountId") Long accountId);

    @Modifying
    @Query(
            "UPDATE WorkspaceAccessRequest r SET r.decidedByAccountId = NULL WHERE r.workspace.id = :workspaceId AND r.decidedByAccountId = :accountId")
    void eraseReviewerReference(@Param("workspaceId") Long workspaceId, @Param("accountId") Long accountId);

    @WorkspaceAgnostic("Portable export reads only the authenticated account's own submissions across workspaces")
    @Query(
            "SELECT r FROM WorkspaceAccessRequest r JOIN FETCH r.workspace WHERE r.accountId = :accountId ORDER BY r.submittedAt, r.id")
    List<WorkspaceAccessRequest> findForAccountExport(@Param("accountId") Long accountId);

    @WorkspaceAgnostic("Retention discovers workspace IDs only; personal data is read under that workspace's lock")
    @Query("SELECT DISTINCT r.workspace.id FROM WorkspaceAccessRequest r ORDER BY r.workspace.id")
    List<Long> findWorkspaceIdsForRetention();

    @Query(
            "SELECT DISTINCT r.accountId FROM WorkspaceAccessRequest r WHERE r.workspace.id = :workspaceId ORDER BY r.accountId")
    List<Long> findAccountIdsInWorkspace(@Param("workspaceId") Long workspaceId);

    void deleteAllByWorkspace_Id(Long workspaceId);

    void deleteAllByWorkspace_IdAndAccountId(Long workspaceId, Long accountId);
}
