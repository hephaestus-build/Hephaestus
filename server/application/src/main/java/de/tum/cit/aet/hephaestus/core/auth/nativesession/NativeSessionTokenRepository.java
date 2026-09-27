package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Credential lineage belongs to an account-scoped native session")
public interface NativeSessionTokenRepository extends JpaRepository<NativeSessionToken, UUID> {
    boolean existsByJtiAndRefreshTokenHash(UUID jti, String refreshTokenHash);

    /** Scalar lookup avoids caching a session entity before acquiring the account and session locks. */
    @Query("""
        SELECT s.accountId FROM NativeSession s, NativeSessionToken t
        WHERE t.sessionId = s.id AND t.refreshTokenHash = :hash
        """)
    Optional<Long> findAccountForSecret(@Param("hash") String hash);
}
