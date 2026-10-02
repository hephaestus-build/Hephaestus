package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
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
@WorkspaceAgnostic("Installed-client sessions are account-scoped, not workspace-scoped")
public interface ClientSessionRepository extends JpaRepository<ClientSession, UUID> {

    /** The owning account, read without loading the row, so the account lock can be taken first. */
    @Query("SELECT s.accountId FROM ClientSession s WHERE s.id = :id")
    Optional<Long> findAccountId(@Param("id") UUID id);

    /** The session row, write-locked for the rest of the transaction. Take the account lock first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ClientSession s WHERE s.id = :id")
    Optional<ClientSession> lockById(@Param("id") UUID id);

    @Modifying
    @Query("""
        UPDATE ClientSession s
           SET s.revokedAt = :now,
               s.revokedReason = :reason
         WHERE s.id = :id
           AND s.revokedAt IS NULL
        """)
    int end(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") IssuedJwt.RevokedReason reason);

    @Modifying
    @Query("""
        UPDATE ClientSession s
           SET s.revokedAt = :now,
               s.revokedReason = :reason
         WHERE s.accountId = :accountId
           AND s.revokedAt IS NULL
        """)
    int endAllForAccount(
            @Param("accountId") Long accountId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    @Modifying
    @Query("""
        UPDATE ClientSession s
           SET s.revokedAt = :now,
               s.revokedReason = :reason
         WHERE s.accountId = :accountId
           AND s.revokedAt IS NULL
           AND s.id <> :keepId
        """)
    int endAllForAccountExcept(
            @Param("accountId") Long accountId,
            @Param("keepId") UUID keepId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /** Sessions that can still refresh, including those whose access token lapsed while the client sat idle. */
    @Query("""
        SELECT s
          FROM ClientSession s
         WHERE s.accountId = :accountId
           AND s.revokedAt IS NULL
           AND s.sessionExpiresAt > :now
        """)
    List<ClientSession> findLiveByAccountId(@Param("accountId") Long accountId, @Param("now") Instant now);

    /**
     * Ids of sessions past their deadline or ended before {@code revokedBefore}, write-locked, a bounded
     * page at a time. Locked rows another transaction holds are skipped for the next run rather than
     * waited on. The cleanup locks sessions before their tokens, the order every session operation uses.
     */
    @Query(value = """
                SELECT id FROM client_session
                 WHERE session_expires_at < :now
                    OR revoked_at < :revokedBefore
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """, nativeQuery = true)
    List<UUID> lockEnded(
            @Param("now") Instant now, @Param("revokedBefore") Instant revokedBefore, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM ClientSession s WHERE s.id IN :ids")
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
