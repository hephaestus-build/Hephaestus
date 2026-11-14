package de.tum.cit.aet.hephaestus.integration.core.connection;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
