package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The lease SQL of the mentor's attempts, on the database clock. Each statement names the account and locks one row
 * kind: the request before the attempt, in separate statements, as the dispatch grant and the commit take them. Claim
 * locks are {@code SKIP LOCKED}, so a claim never waits.
 */
@WorkspaceAgnostic("The mentor's rows are account-owned; row-level security scopes them to the bound account")
public interface MentorAttemptRepository extends Repository<MentorAttempt, UUID> {
    /** The account's serialization row, or nothing while another transition holds it. */
    @Query(
            value = "SELECT account_id FROM mentor_owner WHERE account_id = :account FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Long> lockOwnerOrSkip(@Param("account") long account);

    /** The account's leases that still run; the owner row is held, so no other claim of the account changes them. */
    @Query(value = """
            SELECT count(*) AS total, count(*) FILTER (WHERE work_class = 'BACKGROUND') AS background
            FROM mentor_attempt
            WHERE account_id = :account AND state = 'LEASED' AND leased_until > clock_timestamp()
            """, nativeQuery = true)
    LiveLeases liveLeases(@Param("account") long account);

    /** Attempts of the class that a claim may take, oldest first. It locks nothing; each claim checks again. */
    @Query(value = """
            SELECT a.id AS attempt, a.request_id AS request, a.request_version AS version
            FROM mentor_attempt a
            JOIN mentor_request r ON r.account_id = a.account_id AND r.id = a.request_id
            WHERE a.account_id = :account AND a.work_class = :workClass AND a.deadline > clock_timestamp()
                AND ((a.state = 'QUEUED' AND a.due_at <= clock_timestamp())
                    OR (a.state = 'LEASED' AND a.leased_until <= clock_timestamp() AND a.claims < :maxClaims))
                AND r.state IN ('OPEN', 'RUNNING') AND r.version = a.request_version
                AND r.deadline > clock_timestamp()
            ORDER BY a.due_at, a.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Due> due(
            @Param("account") long account,
            @Param("workClass") String workClass,
            @Param("maxClaims") int maxClaims,
            @Param("limit") int limit);

    /** The live request at this version, or nothing when it moved, settled, expired or is held by another claim. */
    @Query(value = """
            SELECT id FROM mentor_request
            WHERE account_id = :account AND id = :request AND version = :version AND state IN ('OPEN', 'RUNNING')
                AND deadline > clock_timestamp()
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> lockLiveRequestOrSkip(
            @Param("account") long account, @Param("request") UUID request, @Param("version") int version);

    /**
     * Leases a due attempt under a new epoch and token, capped at its work deadline, or nothing when another claim
     * holds it. A lapsed lease is taken again only while its claims last.
     */
    @Query(value = """
            WITH due AS (
                SELECT id FROM mentor_attempt
                WHERE account_id = :account AND id = :attempt AND deadline > clock_timestamp()
                    AND ((state = 'QUEUED' AND due_at <= clock_timestamp())
                        OR (state = 'LEASED' AND leased_until <= clock_timestamp() AND claims < :maxClaims))
                FOR UPDATE SKIP LOCKED),
            leased AS (
                UPDATE mentor_attempt t
                SET state = 'LEASED', lease_epoch = t.lease_epoch + 1, lease_token = :token, claims = t.claims + 1,
                    leased_until = LEAST(clock_timestamp() + make_interval(secs => :lease), t.deadline)
                FROM due WHERE t.id = due.id AND t.account_id = :account
                RETURNING t.lease_epoch AS epoch, t.leased_until AS leased_until)
            SELECT epoch, leased_until AS "leasedUntil" FROM leased
            """, nativeQuery = true)
    Optional<Leased> leaseOrSkip(
            @Param("account") long account,
            @Param("attempt") UUID attempt,
            @Param("token") UUID token,
            @Param("lease") double leaseSeconds,
            @Param("maxClaims") int maxClaims);

    /**
     * Ends the account's queued or lapsed attempts that a newer request version replaced, as {@code VERSION_STALE}. The
     * version only rises, so the request is read without a lock. A held lease fails its next dispatch check instead.
     */
    @Modifying
    @Query(value = """
            WITH stale AS (
                SELECT a.id FROM mentor_attempt a
                JOIN mentor_request r ON r.account_id = a.account_id AND r.id = a.request_id
                WHERE a.account_id = :account AND r.version > a.request_version
                    AND (a.state = 'QUEUED' OR (a.state = 'LEASED' AND a.leased_until <= clock_timestamp()))
                FOR UPDATE OF a SKIP LOCKED)
            UPDATE mentor_attempt t SET state = 'ENDED', end_reason = 'VERSION_STALE', ended_at = clock_timestamp()
            FROM stale WHERE t.id = stale.id AND t.account_id = :account
            """, nativeQuery = true)
    int endStaleOrSkip(@Param("account") long account);

    /** Under the request lock that the claim holds. */
    @Modifying
    @Query(
            value = "UPDATE mentor_request SET state = 'RUNNING' WHERE account_id = :account AND id = :request"
                    + " AND state = 'OPEN'",
            nativeQuery = true)
    int markRunning(@Param("account") long account, @Param("request") UUID request);

    /** The request an attempt answers. It locks nothing: the request is locked next, then the attempt. */
    @Query(
            value = "SELECT request_id AS request, request_version AS version FROM mentor_attempt"
                    + " WHERE account_id = :account AND id = :attempt",
            nativeQuery = true)
    Optional<AttemptOf> attemptOf(@Param("account") long account, @Param("attempt") UUID attempt);

    /** The live request at this version with its stored scope, held against a Stop, an edit or a settle. */
    @Query(value = """
            SELECT audience_transport AS "audienceTransport", audience_connection_id AS "audienceConnectionId",
                audience_container_id AS "audienceContainerId", audience_thread_root AS "audienceThreadRoot",
                scope_account_generation AS "scopeAccountGeneration", scope_reader_epoch AS "scopeReaderEpoch",
                scope_consent_epoch AS "scopeConsentEpoch", scope_authority AS "scopeAuthority",
                scope_workspace_id AS "scopeWorkspaceId", scope_workspace_generation AS "scopeWorkspaceGeneration",
                scope_binding_id AS "scopeBindingId", scope_binding_generation AS "scopeBindingGeneration",
                scope_native_actor_id AS "scopeNativeActorId"
            FROM mentor_request
            WHERE account_id = :account AND id = :request AND version = :version AND state IN ('OPEN', 'RUNNING')
                AND deadline > clock_timestamp()
            FOR SHARE
            """, nativeQuery = true)
    Optional<LiveRequest> shareLiveRequest(
            @Param("account") long account, @Param("request") UUID request, @Param("version") int version);

    /**
     * The attempt when the credential still holds it on its frozen route: whether its observation was bound at this
     * epoch and is still fresh. Held against a reclaim, a renew and the commit.
     */
    @Query(value = """
            SELECT COALESCE(observed_epoch = lease_epoch
                    AND observed_at > clock_timestamp() - make_interval(secs => :fresh), false)
            FROM mentor_attempt
            WHERE account_id = :#{#c.accountId()} AND id = :#{#c.attemptId()} AND lease_epoch = :#{#c.leaseEpoch()}
                AND lease_token = :#{#c.leaseToken()} AND state = 'LEASED' AND leased_until > clock_timestamp()
                AND deadline > clock_timestamp() AND route_model_id = :#{#c.modelId()}
                AND route_connection_id = :#{#c.connectionId()} AND route_payer = :#{#c.payer().name()}
                AND route_workspace_id IS NOT DISTINCT FROM CAST(:#{#c.payerWorkspaceId()} AS bigint)
            FOR SHARE
            """, nativeQuery = true)
    Optional<Boolean> shareHeldAttempt(@Param("c") AttemptCredential credential, @Param("fresh") double freshSeconds);

    /** Extends a running lease, never past the work deadline. */
    @Modifying
    @Query(value = """
            UPDATE mentor_attempt SET leased_until = LEAST(clock_timestamp() + make_interval(secs => :lease), deadline)
            WHERE account_id = :account AND id = :attempt AND lease_epoch = :epoch AND lease_token = :token
                AND state = 'LEASED' AND leased_until > clock_timestamp() AND deadline > clock_timestamp()
            """, nativeQuery = true)
    int renew(
            @Param("account") long account,
            @Param("attempt") UUID attempt,
            @Param("epoch") long epoch,
            @Param("token") UUID token,
            @Param("lease") double leaseSeconds);

    interface LiveLeases {
        long getTotal();

        long getBackground();
    }

    interface Due {
        UUID getAttempt();

        UUID getRequest();

        int getVersion();
    }

    interface Leased {
        long getEpoch();

        Instant getLeasedUntil();
    }

    interface AttemptOf {
        UUID getRequest();

        int getVersion();
    }

    interface LiveRequest {
        String getAudienceTransport();

        @Nullable
        Long getAudienceConnectionId();

        String getAudienceContainerId();

        @Nullable
        String getAudienceThreadRoot();

        long getScopeAccountGeneration();

        long getScopeReaderEpoch();

        long getScopeConsentEpoch();

        String getScopeAuthority();

        @Nullable
        Long getScopeWorkspaceId();

        @Nullable
        Long getScopeWorkspaceGeneration();

        @Nullable
        Long getScopeBindingId();

        @Nullable
        Long getScopeBindingGeneration();

        @Nullable
        String getScopeNativeActorId();
    }
}
