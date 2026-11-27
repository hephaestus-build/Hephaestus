package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HostGrantRepository extends JpaRepository<HostGrant, UUID> {

    /**
     * Whether the grant still stands (ADR 0166 rule 5): it is neither revoked nor expired, its team is not removed, and
     * its account is still a member of that team. The account's own standing is the caller's check.
     */
    @Query("""
        SELECT COUNT(g) > 0 FROM HostGrant g
        JOIN ProjectTeamMember m ON m.teamId = g.teamId AND m.accountId = g.accountId AND m.workspaceId = g.workspaceId
        JOIN ProjectTeam t ON t.id = g.teamId AND t.workspaceId = g.workspaceId
        WHERE g.workspaceId = :workspaceId AND g.id = :grantId
          AND g.revokedAt IS NULL AND g.expiresAt > :now AND t.removedAt IS NULL
        """)
    boolean isStanding(
            @Param("workspaceId") long workspaceId, @Param("grantId") UUID grantId, @Param("now") Instant now);

    @Modifying
    @WorkspaceAgnostic(
            "Signing out everywhere, a block or an account deletion ends the account's grants in every workspace")
    @Query("UPDATE HostGrant g SET g.revokedAt = :at WHERE g.accountId = :accountId AND g.revokedAt IS NULL")
    int revokeAllFor(@Param("accountId") long accountId, @Param("at") Instant at);
}
