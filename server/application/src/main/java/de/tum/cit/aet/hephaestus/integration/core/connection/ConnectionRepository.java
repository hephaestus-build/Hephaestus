package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectionRepository extends JpaRepository<Connection, Long> {
    @WorkspaceAgnostic("Rotates credentials across all workspaces")
    @Query(value = """
                    SELECT id FROM connection
                    WHERE credentials_encrypted IS NOT NULL
                      AND credentials_key_version IS DISTINCT FROM :activeVersion
                      AND credentials_rotation_failed_at IS NULL
                    ORDER BY id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<Long> lockCredentialRotationBatch(
            @Param("activeVersion") int activeVersion, @Param("batchSize") int batchSize);

    /**
     * Serializes connection lifecycle changes with sync-job creation. Both paths acquire this row lock
     * before checking their respective state, closing the check-then-act window in which a job could
     * start while an uninstall was committing.
     */
    @Query(
            value = "SELECT id FROM connection WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE",
            nativeQuery = true)
    Long acquireLifecycleLock(@Param("id") long id, @Param("workspaceId") long workspaceId);

    Optional<Connection> findByWorkspaceIdAndKindAndInstanceKey(
            long workspaceId, IntegrationKind kind, @Nullable String instanceKey);

    List<Connection> findAllByKindAndInstanceKeyInAndState(
            IntegrationKind kind, Collection<String> instanceKeys, IntegrationState state);

    /** Lookup for kinds where there is at most one row per (workspace, kind). */
    Optional<Connection> findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
            long workspaceId, IntegrationKind kind, IntegrationState state);

    default Optional<Connection> findActive(long workspaceId, IntegrationKind kind) {
        return findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(workspaceId, kind, IntegrationState.ACTIVE);
    }

    List<Connection> findByWorkspaceIdAndState(long workspaceId, IntegrationState state);

    /**
     * The Connection by primary key, scoped to its workspace (the predicate the tenancy inspector
     * requires) and deliberately state-agnostic: deactivation cleanup runs after the row left ACTIVE.
     */
    Optional<Connection> findByIdAndWorkspaceId(Long id, long workspaceId);

    @WorkspaceAgnostic("Provider uninstall guard checks whether another workspace still uses the same installation")
    boolean existsByKindAndInstanceKeyAndStateNotAndIdNot(
            IntegrationKind kind, String instanceKey, IntegrationState state, Long id);

    @Query("SELECT c FROM Connection c WHERE c.workspace.id = :workspaceId")
    List<Connection> findByWorkspaceId(@Param("workspaceId") long workspaceId);

    /**
     * Ids of every workspace holding a Connection of the given kind in the given state — the cross-workspace
     * fan-out a periodic sync scheduler enumerates. The emitted SQL selects {@code workspace_id}, so it
     * satisfies the tenancy inspector without a bypass.
     */
    @Query("SELECT DISTINCT c.workspace.id FROM Connection c WHERE c.kind = :kind AND c.state = :state")
    List<Long> findWorkspaceIdsByKindAndState(
            @Param("kind") IntegrationKind kind, @Param("state") IntegrationState state);

    /**
     * Resolves the ACTIVE Outline Connection that registered {@code subscriptionId} — a single
     * indexed probe on {@code ix_connection_outline_subscription} (a partial expression index on
     * {@code (config ->> 'webhookSubscriptionId') WHERE kind='OUTLINE' AND state='ACTIVE'}).
     *
     * <p><b>This runs on the unauthenticated webhook hot path</b>, before the HMAC comparison: the
     * subscription id is attacker-controlled input from the request body. It must therefore cost
     * O(1) regardless of how many workspaces have Outline connected — a per-workspace loop would
     * make every forged POST an unauthenticated 1+N query amplifier ({@code /webhooks/**} is exempt
     * from the auth rate limiter).
     *
     * <p>Native, not JPQL: JPQL cannot express the Postgres {@code ->>} JSONB operator, and the
     * predicate must be literally {@code kind = 'OUTLINE' AND state = 'ACTIVE'} for the planner to
     * pick the partial index. {@code workspace_id} is in the projection, which is what the tenancy
     * statement inspector requires; the query is deliberately cross-workspace because the
     * subscription id IS the tenant selector — see {@code ConnectionService#findOutlineSubscription},
     * which fails closed on a non-unique match.
     *
     * <p>Returns a list (not {@code Optional}) so a corrupt fleet-wide duplicate surfaces as an
     * ambiguity the caller can reject, rather than as a {@code NonUniqueResultException} thrown at
     * an anonymous caller.
     */
    @Query(value = """
        SELECT c.workspace_id AS "workspaceId",
               c.config ->> 'serverUrl' AS "serverUrl",
               c.config ->> 'webhookSecret' AS "signingSecret"
        FROM connection c
        WHERE c.kind = 'OUTLINE'
          AND c.state = 'ACTIVE'
          AND c.config ->> 'webhookSubscriptionId' = :subscriptionId
        """, nativeQuery = true)
    List<OutlineSubscriptionProjection> findOutlineSubscriptionsBySubscriptionId(
            @Param("subscriptionId") String subscriptionId);

    /**
     * The (workspace, encrypted signing secret) pair behind one Outline change-notification
     * subscription. Aliases in the native query above are quoted so the JDBC column labels match
     * these getters exactly (Postgres folds unquoted identifiers to lower-case).
     */
    interface OutlineSubscriptionProjection {
        Long getWorkspaceId();

        @Nullable
        String getServerUrl();

        @Nullable
        String getSigningSecret();
    }

    /**
     * Records that exactly this ciphertext could not be read, and only once: a credential replaced
     * since the failed read has a different ciphertext and is left alone. The record advances the
     * version, so an entity loaded before it and flushed after it fails its optimistic check instead
     * of silently writing the stale value back. It never waits on the target row: a row another
     * transaction holds locked — as disconnect does while it revokes the credential it just read — is
     * skipped, and the record is left for a later read.
     */
    @Modifying
    @Query(value = """
                    UPDATE connection
                    SET credentials_rotation_failed_at = :at, version = version + 1
                    WHERE workspace_id = :workspaceId
                      AND id = (
                          SELECT id FROM connection
                          WHERE id = :id
                            AND workspace_id = :workspaceId
                            AND credentials_encrypted = :ciphertext
                            AND credentials_rotation_failed_at IS NULL
                          FOR UPDATE SKIP LOCKED)
                    """, nativeQuery = true)
    int markCredentialsUnreadable(
            @Param("id") Long id,
            @Param("workspaceId") Long workspaceId,
            @Param("ciphertext") byte[] ciphertext,
            @Param("at") Instant at);

    /** Clears the record once the same ciphertext reads again, as after the right key is restored. */
    @Modifying
    @Query(value = """
                    UPDATE connection
                    SET credentials_rotation_failed_at = NULL, version = version + 1
                    WHERE workspace_id = :workspaceId
                      AND id = (
                          SELECT id FROM connection
                          WHERE id = :id
                            AND workspace_id = :workspaceId
                            AND credentials_encrypted = :ciphertext
                            AND credentials_rotation_failed_at IS NOT NULL
                          FOR UPDATE SKIP LOCKED)
                    """, nativeQuery = true)
    int markCredentialsReadable(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId, @Param("ciphertext") byte[] ciphertext);
}
