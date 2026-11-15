package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface McpClientGrantRepository extends JpaRepository<McpClientGrant, UUID> {

    /** Takes the grant's row lock, last in the order account, session, grant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM McpClientGrant g WHERE g.id = :id")
    @WorkspaceAgnostic("The OAuth endpoints address a grant by its id before a workspace context exists")
    Optional<McpClientGrant> lockById(@Param("id") UUID id);

    /** The grant that a client session holds, read on every MCP request and every refresh. */
    @Query("SELECT g FROM McpClientGrant g WHERE g.clientSessionId = :sessionId AND g.status = 'ACTIVE'")
    @WorkspaceAgnostic("A token names its session, and the grant behind it decides the workspace")
    Optional<McpClientGrant> findActiveBySession(@Param("sessionId") UUID sessionId);

    @Query("""
        SELECT g FROM McpClientGrant g
         WHERE g.workspaceId = :workspaceId AND g.status = 'ACTIVE'
         ORDER BY g.approvedAt DESC
        """)
    List<McpClientGrant> findActiveByWorkspace(@Param("workspaceId") Long workspaceId);

    @Query("SELECT g FROM McpClientGrant g WHERE g.workspaceId = :workspaceId AND g.id = :id")
    Optional<McpClientGrant> findInWorkspace(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /** The owner of a workspace's grant, read as a scalar so the locked read is the first load of the row. */
    @Query("SELECT g.accountId FROM McpClientGrant g WHERE g.workspaceId = :workspaceId AND g.id = :id")
    Optional<Long> findAccountIdInWorkspace(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /** What a grant is for, read as a projection so the exchange's locked read stays the first load of the row. */
    record Target(String resource, String scopes) {}

    @Query("SELECT new de.tum.cit.aet.hephaestus.core.auth.mcp.McpClientGrantRepository$Target(g.resource, g.scopes)"
            + " FROM McpClientGrant g WHERE g.id = :id")
    @WorkspaceAgnostic("The token endpoint reads a code's grant by id before a workspace context exists")
    Optional<Target> findTarget(@Param("id") UUID id);

    /** The grant's current session link, read again under the grant lock to detect a racing exchange. */
    @Query("SELECT g.clientSessionId FROM McpClientGrant g WHERE g.id = :id")
    @WorkspaceAgnostic("Read by id within a revoke whose workspace was checked first")
    Optional<UUID> findSessionId(@Param("id") UUID id);

    /** Revokes an open grant by id; 1 when it was open. */
    @Modifying
    @Query("""
        UPDATE McpClientGrant g
           SET g.status = 'REVOKED', g.endedAt = :now
         WHERE g.id = :id
           AND g.status IN ('PENDING', 'APPROVED', 'ACTIVE')
        """)
    @WorkspaceAgnostic("Revoked by id within a revoke whose workspace was checked first")
    int revokeIfOpen(@Param("id") UUID id, @Param("now") Instant now);

    /** Account-wide revocation ends every grant of the account, so no workspace lists a grant whose session is gone. */
    @Modifying
    @Query("""
        UPDATE McpClientGrant g
           SET g.status = 'REVOKED', g.endedAt = :now
         WHERE g.accountId = :accountId
           AND g.status IN ('PENDING', 'APPROVED', 'ACTIVE')
        """)
    @WorkspaceAgnostic("Signing out everywhere ends the account's grants in every workspace")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("now") Instant now);

    /** As {@link #revokeAllForAccount}, keeping the grant of the session that the caller keeps. */
    @Modifying
    @Query("""
        UPDATE McpClientGrant g
           SET g.status = 'REVOKED', g.endedAt = :now
         WHERE g.accountId = :accountId
           AND g.status IN ('PENDING', 'APPROVED', 'ACTIVE')
           AND (g.clientSessionId IS NULL OR g.clientSessionId <> :exceptSessionId)
        """)
    @WorkspaceAgnostic("Signing out everywhere else ends the account's other grants in every workspace")
    int revokeAllForAccountExceptSession(
            @Param("accountId") Long accountId, @Param("exceptSessionId") UUID exceptSessionId, @Param("now") Instant now);

    /** An ended client session revokes its grant in the same transaction. */
    @Modifying
    @Query("""
        UPDATE McpClientGrant g
           SET g.status = 'REVOKED', g.endedAt = :now
         WHERE g.clientSessionId = :sessionId
           AND g.status = 'ACTIVE'
        """)
    @WorkspaceAgnostic("A session ends without a workspace context, from the session list or a refresh")
    int revokeBySession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    /** Grants whose consent request or code ran out, so the console and the data map see their real state. */
    @Modifying
    @Query("""
        UPDATE McpClientGrant g
           SET g.status = 'EXPIRED', g.endedAt = :now
         WHERE (g.status = 'PENDING' AND g.expiresAt < :now)
            OR (g.status = 'APPROVED' AND g.approvedAt < :codeCutoff)
        """)
    @WorkspaceAgnostic("Expiry is a cleanup across all workspaces")
    int expireStale(@Param("now") Instant now, @Param("codeCutoff") Instant codeCutoff);

    /** Ended grants keep their row for the retention period, so the config history can still name the client. */
    @Modifying
    @Query("DELETE FROM McpClientGrant g WHERE g.endedAt < :cutoff")
    @WorkspaceAgnostic("Retention deletes ended grants across all workspaces")
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}
