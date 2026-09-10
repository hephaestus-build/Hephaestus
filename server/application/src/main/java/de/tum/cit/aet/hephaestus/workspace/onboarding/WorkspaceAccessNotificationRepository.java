package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkspaceAccessNotificationRepository extends JpaRepository<WorkspaceAccessNotification, Long> {
    boolean existsByWorkspace_IdAndRequest_IdAndKind(
            Long workspaceId, Long requestId, WorkspaceAccessNotification.Kind kind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM WorkspaceAccessNotification n WHERE n.workspace.id = :workspaceId AND n.id = :id")
    Optional<WorkspaceAccessNotification> lockInWorkspace(@Param("workspaceId") Long workspaceId, @Param("id") Long id);

    @WorkspaceAgnostic(
            "The server scheduler selects only workspace/id pairs; delivery re-enters that workspace and locks its row")
    @Query("SELECT n.workspace.id AS workspaceId, n.id AS id FROM WorkspaceAccessNotification n "
            + "WHERE n.state = :state "
            + "AND n.nextAttemptAt <= :now ORDER BY n.nextAttemptAt, n.id")
    List<DueNotification> findDue(
            @Param("state") WorkspaceAccessNotification.State state, @Param("now") Instant now, Pageable page);

    List<WorkspaceAccessNotification> findByWorkspace_IdAndRequest_IdOrderById(Long workspaceId, Long requestId);

    void deleteAllByWorkspace_Id(Long workspaceId);

    void deleteAllByWorkspace_IdAndRequest_AccountId(Long workspaceId, Long accountId);

    interface DueNotification {
        Long getWorkspaceId();

        Long getId();
    }
}
