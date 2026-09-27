package de.tum.cit.aet.hephaestus.core.auth.jwt;

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

    @Modifying
    @Query("""
        UPDATE IssuedJwt j
           SET j.revokedAt = :now,
               j.revokedReason = :reason
         WHERE j.jti = :jti
           AND j.revokedAt IS NULL
        """)
    int revoke(@Param("jti") UUID jti, @Param("now") Instant now, @Param("reason") IssuedJwt.RevokedReason reason);

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
     * Periodic cleanup — physically removes expired rows so the table doesn't grow unbounded. The row a
     * live native session currently backs is kept past its expiry: its revocation is what ends that
     * session, so it must stay revocable until the session's own deadline.
     */
    @Modifying
    @Query("""
        DELETE FROM IssuedJwt j
         WHERE j.expiresAt < :cutoff
           AND NOT EXISTS (
                SELECT 1
                  FROM NativeSession s
                 WHERE s.currentJti = j.jti
                   AND s.revokedAt IS NULL
                   AND s.sessionExpiresAt > :cutoff)
        """)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
