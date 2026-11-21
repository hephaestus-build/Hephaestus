package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectionAuditRepository extends JpaRepository<ConnectionAudit, Long> {
    List<ConnectionAudit> findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(long workspaceId, long connectionId);

    /** The caller holds the connection lifecycle lock, so a replay is skipped before erasure or state writes. */
    @Query("SELECT COUNT(a) > 0 FROM ConnectionAudit a WHERE a.connection.id = :connectionId "
            + "AND a.workspaceId = :workspaceId AND a.eventType = :eventType "
            + "AND a.correlationId IS NOT DISTINCT FROM :correlationId")
    boolean containsEvent(
            @Param("connectionId") long connectionId,
            @Param("workspaceId") long workspaceId,
            @Param("eventType") String eventType,
            @Param("correlationId") @Nullable String correlationId);

    /**
     * Removes who acted and why from the rows recorded more than {@code days} ago, in every workspace, as account
     * erasure does. The event, its states, its correlation and its time stay for {@link #containsEvent}.
     */
    @WorkspaceAgnostic("Retention ages out rows across every workspace; there is no single tenant to scope it to")
    @Modifying
    @Query(value = """
        UPDATE connection_audit SET actor_account_id=NULL,actor_ref=NULL,detail=NULL
        WHERE occurred_at < now() - make_interval(days => :days)
          AND (actor_account_id IS NOT NULL OR actor_ref IS NOT NULL OR detail IS NOT NULL)
        """, nativeQuery = true)
    int scrubOlderThan(@Param("days") int days);
}
