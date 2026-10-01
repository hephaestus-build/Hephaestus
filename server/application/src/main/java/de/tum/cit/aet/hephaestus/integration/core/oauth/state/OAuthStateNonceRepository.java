package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link OAuthStateNonce}.
 *
 * <p>Workspace-agnostic — nonces are consumed at the OAuth callback BEFORE
 * any workspace context is established. See {@link OAuthStateNonceStore} for
 * the consume semantics.
 */
@Repository
@WorkspaceAgnostic("Consumed pre-workspace at OAuth callback")
public interface OAuthStateNonceRepository extends JpaRepository<OAuthStateNonce, String> {
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
    int markConsumed(
            @Param("nonce") String nonce,
            @Param("now") Instant now,
            @Param("workspace") long workspaceId,
            @Param("kind") String kind,
            @Param("issued") Instant issuedAt,
            @Param("actor") @Nullable Long actorAccountId);

    /** Cleanup helper — drops rows older than the cutoff. */
    @Modifying
    @Query("DELETE FROM OAuthStateNonce n WHERE n.issuedAt < :cutoff")
    int deleteByIssuedAtBefore(@Param("cutoff") Instant cutoff);
}
