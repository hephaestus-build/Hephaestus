package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("JWT signing keys are system-wide, not workspace-scoped")
public interface JwtSigningKeyRepository extends JpaRepository<JwtSigningKey, String> {
    /**
     * All active keys (these both sign and verify). Ordered so the freshest is first; the issuer picks
     * that one to sign new tokens, while every active key still verifies. The {@code kid DESC}
     * secondary sort is a deterministic tiebreaker: once overlapping-key rotation introduces a second
     * active key, two rows sharing a {@code created_at} (same-millisecond insert / cross-pod clock skew)
     * must still pick the SAME signer cluster-wide, not an arbitrary one.
     */
    @Query("""
        SELECT k FROM JwtSigningKey k
        WHERE k.active = true
        ORDER BY k.createdAt DESC, k.kid DESC
        """)
    List<JwtSigningKey> findActive();

    /** Count active keys so {@code ensureActiveKey()} knows whether to bootstrap the first one. */
    long countByActiveTrue();

    /**
     * Acquire a transaction-scoped advisory lock serializing the one-time signing-key bootstrap across
     * pods. Held until the surrounding {@code @Transactional} ends (auto-released on commit OR rollback),
     * so a cold multi-pod cluster cannot INSERT two active keys (two signing identities) by racing
     * through {@code countByActiveTrue() == 0}.
     *
     * <p>Uses the two-integer advisory key space ({@code classId=17}, ADR 0017 native auth, as a
     * namespace; {@code objId=1}, the single global bootstrap) so it cannot collide with
     * {@code UserRepository#acquireLoginLock} (single-bigint space) — the integer and bigint
     * advisory key spaces are disjoint in Postgres.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(17, 1)", nativeQuery = true)
    void acquireBootstrapLock();
}
