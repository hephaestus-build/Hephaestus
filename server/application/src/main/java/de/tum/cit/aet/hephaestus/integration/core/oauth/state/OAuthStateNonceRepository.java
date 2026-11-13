package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link OAuthStateNonce}.
 *
 * <p>Each nonce names the workspace whose OAuth flow issued it, and the callback consumes it only with that workspace
 * bound ({@link #markConsumedInWorkspace}). See {@link OAuthStateNonceStore} for the consume semantics.
 */
@Repository
public interface OAuthStateNonceRepository extends JpaRepository<OAuthStateNonce, String> {
    Optional<OAuthStateNonce> findByNonceAndWorkspaceId(String nonce, long workspaceId);

    /** Issuing a nonce checks that it is unused. The nonce is the key, unique across every workspace. */
    @Override
    @WorkspaceAgnostic("Issuing a nonce checks that it is unused; the nonce is the key, unique across every workspace")
    boolean existsById(String nonce);

    /**
     * Atomically claim a nonce. The {@code consumed_at IS NULL} guard ensures
     * exactly one caller wins even under concurrent OAuth callback floods (a
     * not-impossible scenario if a vendor retries the callback redirect). The
     * caller MUST treat a return of {@code 0} as "already consumed" — the
     * outer service maps that to a clear rejection.
     *
     * @return 1 if this caller flipped the row from unconsumed → consumed; 0 otherwise.
     */
    @Modifying
    @Query(value = """
            UPDATE oauth_state_nonce n SET consumed_at = :now
            WHERE n.nonce = :nonce AND n.consumed_at IS NULL
              AND n.workspace_id = :workspace AND n.kind = :kind AND n.issued_at = :issued
              AND n.actor_account_id IS NOT DISTINCT FROM CAST(:actor AS bigint)
              AND (n.actor_account_id IS NULL OR EXISTS (
                  SELECT 1 FROM account a WHERE a.id = n.actor_account_id AND a.status = 'ACTIVE'))
            """, nativeQuery = true)
    int markConsumedInWorkspace(
            @Param("nonce") String nonce,
            @Param("now") Instant now,
            @Param("workspace") long workspaceId,
            @Param("kind") String kind,
            @Param("issued") Instant issuedAt,
            @Param("actor") @Nullable Long actorAccountId);

    /** Cleanup helper — drops rows older than the cutoff. */
    @WorkspaceAgnostic("Retention prunes the expired nonces of every workspace")
    @Modifying
    @Query("DELETE FROM OAuthStateNonce n WHERE n.issuedAt < :cutoff")
    int deleteByIssuedAtBefore(@Param("cutoff") Instant cutoff);
}
