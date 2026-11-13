package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every write of an event is a conditional statement on the database clock. A write of a lease holder names its epoch
 * and changes nothing under another one. {@code clock_timestamp()} rather than {@code now()}: a lease is compared with
 * the time of the statement, not with the start of a transaction that may have waited for a lock.
 */
interface ProviderEventRepository extends Repository<ProviderEventRow, Long> {

    /** A claimed event as the claim returns it. */
    interface ClaimedRow {
        long getId();

        @Nullable
        Long getWorkspaceId();

        String getSubject();

        String getHeaders();

        byte[] getPayload();

        Instant getReceivedAt();

        int getAttempt();

        int getFailures();

        int getLeaseEpoch();
    }

    /** A dead event as the dead-letter API shows it. */
    interface DeadRow {
        long getId();

        @Nullable
        Long getWorkspaceId();

        String getProvider();

        String getErrorClass();

        Instant getReceivedAt();

        Instant getFailedAt();

        @Nullable
        Instant getReplayedAt();

        @Nullable
        Long getReplayedBy();
    }

    /** The events of one provider in one open or dead state. */
    interface BacklogRow {
        String getProvider();

        String getState();

        long getEvents();

        long getOldestDueSeconds();
    }

    @WorkspaceAgnostic("The inbox backlog of the whole instance; its gauges name no workspace")
    @Query(value = """
            SELECT provider, state, count(*) AS events,
                   COALESCE(CAST(EXTRACT(EPOCH FROM clock_timestamp() - min(available_at)
                       FILTER (WHERE state = 'QUEUED' AND available_at <= clock_timestamp())) AS bigint), 0)
                       AS "oldestDueSeconds"
            FROM provider_event WHERE state IN ('QUEUED', 'LEASED', 'DEAD')
            GROUP BY provider, state
            """, nativeQuery = true)
    List<BacklogRow> backlog();

    /** One row as the receiver stores it. {@code headers} is a JSON object. */
    @SuppressWarnings("ArrayRecordComponent") // The payload is the bytes as received; the record is only a parameter.
    record NewEvent(
            @Nullable Long workspaceId,
            String provider,
            String subject,
            String scopeKey,
            String deliveryKey,
            String headers,
            byte[] payload) {}

    @WorkspaceAgnostic(
            "The receiver stores one row for each workspace that the delivery reaches, and installation rows")
    @Modifying
    @Query(value = """
            INSERT INTO provider_event
              (workspace_id, provider, subject, scope_key, delivery_key, headers, payload, received_at, available_at,
               state, attempt, failures, lease_epoch)
            VALUES (CAST(:#{#event.workspaceId()} AS bigint), :#{#event.provider()}, :#{#event.subject()},
                    :#{#event.scopeKey()}, :#{#event.deliveryKey()}, CAST(:#{#event.headers()} AS jsonb),
                    :#{#event.payload()}, clock_timestamp(), clock_timestamp(), 'QUEUED', 0, 0, 0)
            ON CONFLICT (workspace_id, provider, delivery_key) DO NOTHING
            """, nativeQuery = true)
    int store(@Param("event") NewEvent event);

    /** {@code SET LOCAL statement_timeout}: the bound ends with the caller's transaction. */
    @WorkspaceAgnostic("Sets a bound of the current transaction; it reads no table")
    @Query(value = "SELECT set_config('statement_timeout', :timeout, true)", nativeQuery = true)
    String boundStatements(@Param("timeout") String timeout);

    @WorkspaceAgnostic("The claim offers the queued events of every workspace and of the installation")
    @Query(value = """
            WITH picked AS (
              SELECT e.id FROM provider_event e
              WHERE e.state = 'QUEUED' AND e.available_at <= clock_timestamp()
                AND (e.workspace_id IS NULL OR EXISTS (SELECT 1 FROM workspace w
                                                       WHERE w.id = e.workspace_id AND w.status = 'ACTIVE'))
                AND NOT EXISTS (SELECT 1 FROM provider_event o
                                WHERE o.scope_key = e.scope_key AND o.state IN ('QUEUED', 'LEASED')
                                  AND (o.state = 'LEASED' OR o.id < e.id))
              ORDER BY e.available_at, e.id
              LIMIT 1 FOR UPDATE OF e SKIP LOCKED)
            UPDATE provider_event e SET state = 'LEASED', attempt = e.attempt + 1, lease_epoch = e.lease_epoch + 1,
                   leased_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
              FROM picked WHERE e.id = picked.id
            RETURNING e.id AS id, e.workspace_id AS "workspaceId", e.subject AS subject,
                      CAST(e.headers AS text) AS headers, e.payload AS payload, e.received_at AS "receivedAt",
                      e.attempt AS attempt, e.failures AS failures, e.lease_epoch AS "leaseEpoch"
            """, nativeQuery = true)
    Optional<ClaimedRow> claim(@Param("leaseSeconds") long leaseSeconds);

    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    @Modifying
    @Query(value = """
            UPDATE provider_event SET state = 'DONE', leased_until = NULL, ended_at = clock_timestamp()
            WHERE id = :id AND state = 'LEASED' AND lease_epoch = :epoch
            """, nativeQuery = true)
    int complete(@Param("id") long id, @Param("epoch") int epoch);

    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    @Modifying
    @Query(value = """
            UPDATE provider_event SET state = 'QUEUED', leased_until = NULL,
                   failures = failures + CASE WHEN :counted THEN 1 ELSE 0 END,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds)
            WHERE id = :id AND state = 'LEASED' AND lease_epoch = :epoch
            """, nativeQuery = true)
    int retry(
            @Param("id") long id,
            @Param("epoch") int epoch,
            @Param("delaySeconds") double delaySeconds,
            @Param("counted") boolean counted);

    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    @Modifying
    @Query(value = """
            UPDATE provider_event SET state = 'DEAD', leased_until = NULL, ended_at = clock_timestamp(),
                   error_class = :errorClass
            WHERE id = :id AND state = 'LEASED' AND lease_epoch = :epoch
            """, nativeQuery = true)
    int dead(@Param("id") long id, @Param("epoch") int epoch, @Param("errorClass") String errorClass);

    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    @Modifying
    @Query(value = """
            WITH held AS (
              SELECT id FROM provider_event WHERE id = :id AND state = 'LEASED' AND lease_epoch = :epoch
              FOR NO KEY UPDATE SKIP LOCKED)
            UPDATE provider_event e SET leased_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
            FROM held WHERE e.id = held.id
            """, nativeQuery = true)
    int renew(@Param("id") long id, @Param("epoch") int epoch, @Param("leaseSeconds") long leaseSeconds);

    @WorkspaceAgnostic("The event id and its lease epoch identify the claimed row")
    @Modifying
    @Query(value = """
            UPDATE provider_event SET state = 'QUEUED', leased_until = NULL, attempt = attempt - 1,
                   available_at = clock_timestamp()
            WHERE id = :id AND state = 'LEASED' AND lease_epoch = :epoch
            """, nativeQuery = true)
    int release(@Param("id") long id, @Param("epoch") int epoch);

    @WorkspaceAgnostic("The reaper takes back the expired leases of every workspace")
    @Modifying
    @Query(value = """
            WITH expired AS (
              SELECT id FROM provider_event WHERE state = 'LEASED' AND leased_until < clock_timestamp()
              FOR UPDATE SKIP LOCKED)
            UPDATE provider_event e SET leased_until = NULL, failures = failures + 1,
                   state = CASE WHEN failures + 1 >= :maxAttempts THEN 'DEAD' ELSE 'QUEUED' END,
                   ended_at = CASE WHEN failures + 1 >= :maxAttempts THEN clock_timestamp() END,
                   error_class = CASE WHEN failures + 1 >= :maxAttempts THEN 'LeaseExpired' END,
                   available_at = clock_timestamp()
            FROM expired WHERE e.id = expired.id
            """, nativeQuery = true)
    int reapExpired(@Param("maxAttempts") int maxAttempts);

    interface LeaseRow {
        String getState();

        int getLeaseEpoch();
    }

    String LEASE_READ = """
            SELECT state, lease_epoch AS "leaseEpoch" FROM provider_event WHERE id = :id
            """;
    String LEASE_LOCK = LEASE_READ + " FOR SHARE";

    @WorkspaceAgnostic("The workspace is read only through the trusted event id; no caller supplies another tenant")
    @Query(value = """
            SELECT w.id FROM workspace w
            WHERE w.id = (SELECT workspace_id FROM provider_event WHERE id = :id)
            FOR KEY SHARE NOWAIT
            """, nativeQuery = true)
    Optional<Long> lockWorkspace(@Param("id") long id);

    @WorkspaceAgnostic("The event id and epoch come from the trusted claim, never from a request")
    @Query(value = LEASE_READ + " FOR KEY SHARE NOWAIT", nativeQuery = true)
    Optional<LeaseRow> readLease(@Param("id") long id);

    interface ErasedRow extends LeaseRow {
        long getId();
    }

    /** SHARE permits independent nested writers, but blocks erasure and lease replacement until commit. */
    @WorkspaceAgnostic("The event id and epoch come from the trusted claim, never from a request")
    @Query(value = LEASE_LOCK + " NOWAIT", nativeQuery = true)
    Optional<LeaseRow> lockLease(@Param("id") long id);

    /** Outcome recording holds no domain locks and can wait for a concurrent erasure to commit. */
    @WorkspaceAgnostic("The event id and epoch come from the trusted claim, never from a request")
    @Query(value = LEASE_LOCK, nativeQuery = true)
    Optional<LeaseRow> awaitLease(@Param("id") long id);

    @WorkspaceAgnostic("The event id identifies the claimed row")
    @Modifying
    @Query(value = """
            INSERT INTO provider_event_step (event_id, step_name, processed_at)
            VALUES (:eventId, :step, clock_timestamp())
            ON CONFLICT (event_id, step_name) DO NOTHING
            """, nativeQuery = true)
    int claimStep(@Param("eventId") long eventId, @Param("step") String step);

    @WorkspaceAgnostic("The event id identifies the claimed row")
    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM provider_event_step WHERE event_id = :eventId AND step_name = :step)
            """, nativeQuery = true)
    boolean hasStep(@Param("eventId") long eventId, @Param("step") String step);

    @WorkspaceAgnostic("Retention removes the ended events of every workspace")
    @Modifying
    @Query(value = """
            DELETE FROM provider_event
            WHERE (state = 'DONE' AND ended_at < :doneBefore) OR (state = 'DEAD' AND ended_at < :deadBefore)
            """, nativeQuery = true)
    int deleteEnded(@Param("doneBefore") Instant doneBefore, @Param("deadBefore") Instant deadBefore);

    @Query(value = """
            DELETE FROM provider_event WHERE workspace_id = :workspaceId
            RETURNING id, state, lease_epoch AS "leaseEpoch"
            """, nativeQuery = true)
    List<ErasedRow> deleteWorkspace(@Param("workspaceId") long workspaceId);

    @Query(value = """
            DELETE FROM provider_event
            WHERE workspace_id = :workspaceId AND provider = :provider
              AND split_part(subject, '.', 3) = :resource RETURNING id, state, lease_epoch AS "leaseEpoch"
            """, nativeQuery = true)
    List<ErasedRow> deleteResource(
            @Param("workspaceId") long workspaceId,
            @Param("provider") String provider,
            @Param("resource") String resource);

    @WorkspaceAgnostic("The workspace is bound explicitly; null selects only installation rows")
    @Query(value = """
            SELECT id, workspace_id AS "workspaceId", provider, error_class AS "errorClass", received_at AS "receivedAt",
                   ended_at AS "failedAt", replayed_at AS "replayedAt", replayed_by AS "replayedBy"
            FROM provider_event
            WHERE state = 'DEAD' AND workspace_id IS NOT DISTINCT FROM CAST(:workspaceId AS bigint) AND id > :idAfter
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<DeadRow> deadLetters(
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("idAfter") long idAfter,
            @Param("limit") int limit);

    @WorkspaceAgnostic("The workspace is bound explicitly; null selects only installation rows")
    @Query(value = """
            WITH old AS (
              SELECT id, error_class, ended_at FROM provider_event
              WHERE id = :id AND state = 'DEAD' AND workspace_id IS NOT DISTINCT FROM CAST(:workspaceId AS bigint)
              FOR UPDATE)
            UPDATE provider_event e SET state = 'QUEUED', attempt = 0, failures = 0, available_at = clock_timestamp(),
                   ended_at = NULL, error_class = NULL, replayed_at = clock_timestamp(), replayed_by = :actor
              FROM old WHERE e.id = old.id
            RETURNING e.id AS id, e.workspace_id AS "workspaceId", e.provider AS provider,
                      old.error_class AS "errorClass", e.received_at AS "receivedAt", old.ended_at AS "failedAt",
                      e.replayed_at AS "replayedAt", e.replayed_by AS "replayedBy"
            """, nativeQuery = true)
    Optional<DeadRow> replay(
            @Param("workspaceId") @Nullable Long workspaceId, @Param("id") long id, @Param("actor") long actor);
}
