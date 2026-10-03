package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

@WorkspaceAgnostic("Instance-admin rights requests intentionally span workspaces")
interface PersonDataRequestRepository extends JpaRepository<PersonDataRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PersonDataRequest r where r.id = :id")
    Optional<PersonDataRequest> lock(UUID id);
}
