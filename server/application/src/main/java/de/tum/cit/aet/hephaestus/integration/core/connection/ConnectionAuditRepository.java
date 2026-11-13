package de.tum.cit.aet.hephaestus.integration.core.connection;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectionAuditRepository extends JpaRepository<ConnectionAudit, Long> {
    List<ConnectionAudit> findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(long workspaceId, long connectionId);
}
