package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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
@WorkspaceAgnostic("JWT revocation list is account-scoped, not workspace-scoped")
public interface IssuedJwtRepository extends JpaRepository<IssuedJwt, UUID> {
    /**
     * Used by {@code RevocationAwareJwtDecoder} on every authenticated request via a Caffeine cache.
     * Returns {@link Optional#empty()} for tokens never issued, revoked, or expired — all three are
     * indistinguishable to the decoder (it just refuses the JWT).
     */
    @Lock(LockModeType.NONE)
    @Query("""
        SELECT j
          FROM IssuedJwt j
         WHERE j.jti = :jti
           AND j.revokedAt IS NULL
           AND j.expiresAt > :now
        """)
    Optional<IssuedJwt> findActive(@Param("jti") UUID jti, @Param("now") Instant now);

    /** The unrevoked, unused successor; the caller checks its expiry under the predecessor lock. */
    @Query("""
        SELECT j FROM IssuedJwt j
        WHERE j.predecessorJti = :jti AND j.accountId = :accountId
          AND j.usedAt IS NULL AND j.revokedAt IS NULL
        """)
    Optional<IssuedJwt> findUnusedSuccessor(@Param("accountId") Long accountId, @Param("jti") UUID jti);

    /** Includes revoked successors so replacement does not restart the first-refresh window. */
    @Query("""
        SELECT MIN(j.issuedAt) FROM IssuedJwt j
         WHERE j.accountId = :accountId AND j.predecessorJti = :jti
        """)
    Optional<Instant> findFirstSuccessorIssuedAt(@Param("accountId") Long accountId, @Param("jti") UUID jti);

    /** Locks a browser predecessor before reading or changing its unused successor. */
    @Query(value = "SELECT jti FROM issued_jwt WHERE jti = :jti FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForRotation(@Param("jti") UUID jti);

    /**
     * Marks a successor as used. The update re-checks that the token is still active under its row lock,
     * so a concurrent revocation wins or loses as a whole: 1 when
     * the token stays admitted, 0 when it was revoked or expired meanwhile. It keeps the first timestamp
     * and the predecessor link so a racing logout can still follow it. It clears the persistence
     * context, so a later read sees that outcome.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE IssuedJwt j
           SET j.usedAt = COALESCE(j.usedAt, :now)
         WHERE j.jti = :jti
           AND j.revokedAt IS NULL
           AND j.expiresAt > :now
        """)
    int markUsed(@Param("jti") UUID jti, @Param("now") Instant now);

    /**
     * Revokes the owned successors of {@code predecessorJti} that were never used.
     */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.predecessorJti = :predecessorJti
           AND j.accountId = :accountId
           AND j.usedAt IS NULL
           AND j.revokedAt IS NULL
        """)
    int revokeUnusedSuccessors(
            @Param("accountId") Long accountId,
            @Param("predecessorJti") UUID predecessorJti,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /** Revokes browser descendants even when first use already retired their predecessor. The account is write-locked. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        WITH RECURSIVE chain AS (
            SELECT jti FROM issued_jwt
             WHERE account_id = :accountId AND session_id IS NULL
               AND (jti = :jti OR predecessor_jti = :jti)
            UNION
            SELECT child.jti FROM issued_jwt child JOIN chain parent ON child.predecessor_jti = parent.jti
             WHERE child.account_id = :accountId AND child.session_id IS NULL
        )
        UPDATE issued_jwt SET revoked_at = :now, revoked_reason = :reason
         WHERE account_id = :accountId AND jti IN (SELECT jti FROM chain) AND revoked_at IS NULL
        """, nativeQuery = true)
    int revokeBrowserDescendants(
            @Param("accountId") Long accountId,
            @Param("jti") UUID jti,
            @Param("now") Instant now,
            @Param("reason") String reason);

    /**
     * Ownership-scoped single-session revoke (self/admin "revoke this session"). Atomic
     * check-and-revoke in one UPDATE — the {@code accountId} predicate replaces a prior
     * {@code findById}-then-filter TOCTOU and IS the access control (one account can never revoke
     * another's session). Returns the rows revoked (0 when the jti is missing, not owned, or already
     * revoked).
     */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.jti = :jti
           AND j.accountId = :accountId
           AND j.revokedAt IS NULL
        """)
    int revokeOwned(
            @Param("jti") UUID jti,
            @Param("accountId") Long accountId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.accountId = :accountId
           AND j.revokedAt IS NULL
        """)
    int revokeAllForAccount(
            @Param("accountId") Long accountId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /**
     * Active (non-revoked, non-expired) sessions for an account. Replaces a {@code findAll()}-then-
     * filter on {@code AuthSessionService.activeSessions} (was a full table scan per /user/sessions
     * read). Keyed on the indexed {@code account_id} column.
     */
    @Query("""
        SELECT j
          FROM IssuedJwt j
         WHERE j.accountId = :accountId
           AND j.revokedAt IS NULL
           AND j.expiresAt > :now
        """)
    List<IssuedJwt> findActiveByAccountId(@Param("accountId") Long accountId, @Param("now") Instant now);

    /**
     * Revoke every active session for an account except {@code keepJti} (sign-out-everywhere). A
     * single bulk UPDATE replaces a {@code findAll()}-then-filter-then-revoke-each loop. Returns the
     * number of rows revoked.
     */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.accountId = :accountId
           AND j.revokedAt IS NULL
           AND j.jti <> :keepJti
        """)
    int revokeAllForAccountExcept(
            @Param("accountId") Long accountId,
            @Param("keepJti") UUID keepJti,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /**
     * Account-wide revocation that keeps one installed-client session's whole token family (sign out
     * everywhere else from that client). Browser tokens have no session and are always revoked.
     */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.accountId = :accountId
           AND j.revokedAt IS NULL
           AND (j.sessionId IS NULL OR j.sessionId <> :keepSessionId)
        """)
    int revokeAllForAccountExceptSession(
            @Param("accountId") Long accountId,
            @Param("keepSessionId") UUID keepSessionId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /**
     * The installed-client session a token belongs to, for revoking it from the session list. Resolves
     * rotated-away and expired tokens too, as long as their session exists. Empty for a browser token.
     */
    @Query("SELECT j.sessionId FROM IssuedJwt j WHERE j.jti = :jti AND j.accountId = :accountId")
    Optional<UUID> findSessionIdOwnedBy(@Param("jti") UUID jti, @Param("accountId") Long accountId);

    /** The installed-client session a refresh secret, current or rotated away, was issued to. */
    @Query("SELECT j.sessionId FROM IssuedJwt j WHERE j.refreshTokenHash = :refreshTokenHash")
    Optional<UUID> findSessionIdByRefreshTokenHash(@Param("refreshTokenHash") String refreshTokenHash);

    /**
     * Rotates a session's current token away: 1 when {@code refreshTokenHash} belongs to the session's
     * one unrevoked token, 0 when it was rotated away already (a reuse). Callers hold the session lock.
     */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt.RevokedReason.ROTATE
         WHERE j.refreshTokenHash = :refreshTokenHash
           AND j.sessionId = :sessionId
           AND j.revokedAt IS NULL
        """)
    int rotate(
            @Param("refreshTokenHash") String refreshTokenHash,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    /** Revokes every still-active token of one installed-client session. Callers hold the session lock. */
    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.sessionId = :sessionId
           AND j.revokedAt IS NULL
        """)
    int revokeSession(
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now,
            @Param("reason") IssuedJwt.RevokedReason reason);

    /** The one unrevoked token of each given session: the token a session list shows it by. */
    @Query("""
        SELECT j
          FROM IssuedJwt j
         WHERE j.sessionId IN :sessionIds
           AND j.revokedAt IS NULL
        """)
    List<IssuedJwt> findCurrentBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);

    /**
     * Periodic cleanup — physically removes expired rows so the table doesn't grow unbounded. A row of an
     * installed-client session stays while its session exists: it is how an old refresh secret or JTI
     * still resolves its family. The session cleanup removes those rows with their session.
     */
    @Modifying
    @Query("""
        DELETE FROM IssuedJwt j
         WHERE j.expiresAt < :cutoff
           AND (j.sessionId IS NULL
                OR NOT EXISTS (SELECT 1 FROM ClientSession s WHERE s.id = j.sessionId))
        """)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /** Removes the token rows of sessions the session cleanup has locked and is about to delete. */
    @Modifying
    @Query("DELETE FROM IssuedJwt j WHERE j.sessionId IN :sessionIds")
    int deleteBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);
}
