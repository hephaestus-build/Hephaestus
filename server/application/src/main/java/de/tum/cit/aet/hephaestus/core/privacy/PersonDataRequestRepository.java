package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@WorkspaceAgnostic("Instance-admin rights requests intentionally span workspaces")
interface PersonDataRequestRepository extends JpaRepository<PersonDataRequest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PersonDataRequest r where r.id = :id")
    Optional<PersonDataRequest> lock(UUID id);

    @Transactional(readOnly = true)
    @Query(value = """
            SELECT id FROM person_data_request
            WHERE state IN ('ERASING','FAILED') AND scope_json IS NOT NULL
              AND ((scope_json::jsonb)->>'accountId'=CAST(:account AS text)
                OR EXISTS (SELECT 1 FROM jsonb_array_elements((scope_json::jsonb)->'identities') stored
                  JOIN jsonb_array_elements(CAST(:identities AS jsonb)) chosen ON stored=chosen))
            ORDER BY created_at
            """, nativeQuery = true)
    List<UUID> findInterruptedForPerson(
            @Param("account") @Nullable Long accountId, @Param("identities") String identitiesJson);
}
