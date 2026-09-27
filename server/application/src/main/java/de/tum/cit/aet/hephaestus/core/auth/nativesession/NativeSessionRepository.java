package de.tum.cit.aet.hephaestus.core.auth.nativesession;

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
@WorkspaceAgnostic("Native app sessions are account-scoped, like issued_jwt")
public interface NativeSessionRepository extends JpaRepository<NativeSession, UUID> {

    /** Account lock must be acquired first; the immutable lineage survives any number of rotations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM NativeSession s
        WHERE EXISTS (SELECT 1 FROM NativeSessionToken t WHERE t.sessionId = s.id AND t.refreshTokenHash = :hash)
        """)
    Optional<NativeSession> lockBySecretHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM NativeSession s
        WHERE s.accountId = :accountId AND s.revokedAt IS NULL
          AND EXISTS (SELECT 1 FROM NativeSessionToken t WHERE t.sessionId = s.id AND t.jti = :jti)
        """)
    List<NativeSession> findLiveByAccountAndJti(@Param("accountId") Long accountId, @Param("jti") UUID jti);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM NativeSession s
        WHERE s.accountId = :accountId AND s.revokedAt IS NULL
        ORDER BY s.id
        """)
    List<NativeSession> lockLiveByAccountId(@Param("accountId") Long accountId);

    /** Unrevoked, unexpired sessions: the access tokens they back stay listed after the token itself expires. */
    @Query("""
        SELECT s
          FROM NativeSession s
         WHERE s.accountId = :accountId
           AND s.revokedAt IS NULL
           AND s.sessionExpiresAt > :now
        """)
    List<NativeSession> findLiveByAccountId(@Param("accountId") Long accountId, @Param("now") Instant now);

    @Modifying
    @Query("""
        DELETE FROM NativeSession s
         WHERE s.sessionExpiresAt < :now
            OR s.revokedAt < :revokedBefore
        """)
    int deleteEnded(@Param("now") Instant now, @Param("revokedBefore") Instant revokedBefore);
}
