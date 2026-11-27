package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HostGrantRepository extends JpaRepository<HostGrant, UUID> {

    @Modifying
    @WorkspaceAgnostic("Signing out everywhere, a block or an account deletion ends the account's grants in every"
            + " workspace")
    @Query("UPDATE HostGrant g SET g.revokedAt = :at WHERE g.accountId = :accountId AND g.revokedAt IS NULL")
    int revokeAllFor(@Param("accountId") long accountId, @Param("at") Instant at);
}
