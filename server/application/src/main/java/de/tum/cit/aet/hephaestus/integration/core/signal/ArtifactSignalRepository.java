package de.tum.cit.aet.hephaestus.integration.core.signal;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write here is a single statement the database arbitrates — an insert that yields to whoever
 * got there first, or an update that names the state it expects to find. Read-modify-write through
 * the persistence context would let two observers of the same occurrence both decide to review it.
 */
public interface ArtifactSignalRepository extends JpaRepository<ArtifactSignal, UUID> {
    /** @return 1 when this call created the row, 0 when it was already there */
    @Modifying
    @Query(value = """
        INSERT INTO artifact_signal (
            id, workspace_id, artifact_kind, artifact_id, signal_name, revision,
            occurred_at, discovered_via, state, state_changed_at
        ) VALUES (
            :id, :#{#key.workspaceId()}, :#{#key.artifactKind().value()}, :#{#key.artifactId()},
            :#{#key.signalName().value()}, :#{#key.revision().value()},
            :occurredAt, :discoveredVia, 'RECORDED', :now
        ) ON CONFLICT (workspace_id, artifact_kind, artifact_id, signal_name, revision) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") SignalKey key,
            @Param("id") UUID id,
            @Param("occurredAt") Instant occurredAt,
            @Param("discoveredVia") String discoveredVia,
            @Param("now") Instant now);

    /**
     * Claims a new or undecided occasion; decided rows cannot be reclaimed.
     *
     * @return 1 when this call now owns the signal, 0 when someone already decided it
     */
    @Modifying
    @Query(value = """
        INSERT INTO artifact_signal (
            id, workspace_id, artifact_kind, artifact_id, signal_name, revision,
            occurred_at, discovered_via, state, state_changed_at, requested_by_user_id
        ) VALUES (
            :id, :#{#key.workspaceId()}, :#{#key.artifactKind().value()}, :#{#key.artifactId()},
            :#{#key.signalName().value()}, :#{#key.revision().value()},
            :occurredAt, :discoveredVia, 'RECORDED', :now, :requestedByUserId
        ) ON CONFLICT (workspace_id, artifact_kind, artifact_id, signal_name, revision) DO UPDATE
        SET discovered_via = EXCLUDED.discovered_via,
            occurred_at = EXCLUDED.occurred_at,
            state_changed_at = EXCLUDED.state_changed_at,
            requested_by_user_id = EXCLUDED.requested_by_user_id
        WHERE artifact_signal.state = 'RECORDED'
        """, nativeQuery = true)
    int insertOrClaimUndecided(
            @Param("key") SignalKey key,
            @Param("id") UUID id,
            @Param("occurredAt") Instant occurredAt,
            @Param("discoveredVia") String discoveredVia,
            @Param("now") Instant now,
            @Param("requestedByUserId") @Nullable Long requestedByUserId);

    @Modifying
    @Query(value = """
        INSERT INTO artifact_signal (
            id, workspace_id, artifact_kind, artifact_id, signal_name, revision,
            occurred_at, discovered_via, state, state_changed_at, actor_user_id
        ) VALUES (
            :id, :#{#key.workspaceId()}, :#{#key.artifactKind().value()}, :#{#key.artifactId()},
            :#{#key.signalName().value()}, :#{#key.revision().value()},
            :occurredAt, 'EVENT', 'DEFERRED', :now, :actorUserId
        ) ON CONFLICT (workspace_id, artifact_kind, artifact_id, signal_name, revision) DO UPDATE
        SET discovered_via = 'EVENT', occurred_at = EXCLUDED.occurred_at,
            state = 'DEFERRED', state_reason = NULL, last_attempted_at = NULL,
            state_changed_at = EXCLUDED.state_changed_at,
            actor_user_id = EXCLUDED.actor_user_id
        WHERE (artifact_signal.state = 'RECORDED' AND artifact_signal.discovered_via = 'SYNC')
           OR (artifact_signal.state = 'SUPPRESSED' AND artifact_signal.state_reason = 'COALESCED')
        """, nativeQuery = true)
    int insertDeferred(
            @Param("key") SignalKey key,
            @Param("id") UUID id,
            @Param("occurredAt") Instant occurredAt,
            @Param("now") Instant now,
            @Param("actorUserId") @Nullable Long actorUserId);

    /** Candidates only: consumers must lock each group and recheck eligibility. */
    @WorkspaceAgnostic("Discovers deferred occasions across the instance; settlement is workspace-scoped")
    @Query(value = """
        SELECT workspace_id AS "workspaceId", artifact_id AS "artifactId"
        FROM artifact_signal
        WHERE state = 'DEFERRED' AND signal_name = :signalName
        GROUP BY workspace_id, artifact_id
        HAVING MAX(state_changed_at) <= :quietBefore OR MIN(state_changed_at) <= :deadline
        ORDER BY MIN(COALESCE(last_attempted_at, state_changed_at)), workspace_id, artifact_id
        LIMIT :batchSize
        """, nativeQuery = true)
    List<DeferredArtifact> findDueDeferred(
            @Param("signalName") String signalName,
            @Param("quietBefore") Instant quietBefore,
            @Param("deadline") Instant deadline,
            @Param("batchSize") int batchSize);

    /** Commit separately from settlement so a failed artifact does not monopolize the batch. */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE artifact_signal SET last_attempted_at = :now
        WHERE workspace_id = :workspaceId AND artifact_id = :artifactId AND signal_name = :signalName
          AND state = 'DEFERRED'
        """, nativeQuery = true)
    int noteDeferredAttempt(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactId") Long artifactId,
            @Param("signalName") String signalName,
            @Param("now") Instant now);

    /**
     * Do not use SKIP LOCKED: consumers must settle whole groups, not competing subsets.
     *
     * <p>The second line of defence, not the first: a scheduled sweeper takes a {@code SchedulerLock}
     * so only one replica ever calls this, but should two callers still race, PostgreSQL re-evaluates
     * this predicate against each row after the lock is granted (EvalPlanQual) — the loser's rows are
     * by then no longer {@code DEFERRED} and it locks nothing.
     */
    @Query(value = """
        SELECT * FROM artifact_signal
        WHERE workspace_id = :workspaceId AND artifact_id = :artifactId AND signal_name = :signalName
          AND state = 'DEFERRED'
        ORDER BY revision
        FOR UPDATE
        """, nativeQuery = true)
    List<ArtifactSignal> lockDeferred(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactId") Long artifactId,
            @Param("signalName") String signalName);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM artifact_signal
            WHERE workspace_id = :#{#key.workspaceId()} AND artifact_kind = :#{#key.artifactKind().value()}
              AND artifact_id = :#{#key.artifactId()} AND signal_name = :#{#key.signalName().value()}
              AND revision = :#{#key.revision().value()} AND state = 'DEFERRED'
        )
        """, nativeQuery = true)
    boolean isDeferred(@Param("key") SignalKey key);

    interface DeferredArtifact {
        Long getWorkspaceId();

        Long getArtifactId();
    }

    /**
     * The artifact half of the limit on hand-requested reviews. The workspace's ordinary cooldown cannot
     * serve as it: that cooldown is keyed on an idempotency key whose phase segment is the trigger
     * signal, and a request occupies a phase of its own, so it never lands in the same lane as the
     * lifecycle review it repeats. Counts asks, not reviews — a refused ask is the one worth damping.
     */
    @Query("SELECT COUNT(s) > 0 FROM ArtifactSignal s WHERE s.workspace.id = :workspaceId"
            + " AND s.artifactKind = :artifactKind AND s.artifactId = :artifactId"
            + " AND s.discoveredVia = de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia.MANUAL"
            + " AND s.occurredAt >= :since")
    boolean existsManualRequestSince(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") String artifactKind,
            @Param("artifactId") Long artifactId,
            @Param("since") Instant since);

    /**
     * Takes a collection because one person is several SCM identities; counting per identity would hand a
     * linked account one allowance per provider, which is the same person asking twice as often.
     */
    @Query("SELECT COUNT(s) FROM ArtifactSignal s WHERE s.workspace.id = :workspaceId"
            + " AND s.requestedByUserId IN :requesterIds AND s.occurredAt >= :since")
    long countRequestsBySince(
            @Param("workspaceId") Long workspaceId,
            @Param("requesterIds") Collection<Long> requesterIds,
            @Param("since") Instant since);

    /**
     * The state predicate makes this an arbitration rather than a blind overwrite: without it a late
     * duplicate could re-point a {@code LAPSED} or already-{@code TRIGGERED} row at a second job and lose
     * the first job's id.
     *
     * @return rows updated (0 or 1); 0 means the signal was not where the caller left it.
     */
    @Modifying
    @Query(value = """
        UPDATE artifact_signal
        SET state = 'TRIGGERED', state_reason = NULL, job_id = :jobId, state_changed_at = :now
        WHERE workspace_id = :#{#key.workspaceId()}
          AND artifact_kind = :#{#key.artifactKind().value()}
          AND artifact_id = :#{#key.artifactId()}
          AND signal_name = :#{#key.signalName().value()}
          AND revision = :#{#key.revision().value()}
          AND state IN ('RECORDED', 'DEFERRED', 'PENDING')
        """, nativeQuery = true)
    int markTriggered(@Param("key") SignalKey key, @Param("jobId") UUID jobId, @Param("now") Instant now);

    /**
     * Same predicate as {@link #markTriggered}, and in particular a refusal must not walk a
     * {@code LAPSED} row back to {@code PENDING}, which would re-enter it into the reaper's sweep and
     * defeat the deadline that retired it.
     *
     * <p>{@code state_changed_at} moves only when the state does: restamping on every refusal would push
     * the lapse deadline out by one retry interval per retry interval, so nothing would ever lapse.
     *
     * @return rows updated (0 or 1); 0 means the signal was not where the caller left it.
     */
    @Modifying
    @Query(value = """
        UPDATE artifact_signal
        SET state = :state,
            state_reason = :stateReason,
            state_changed_at = CASE WHEN artifact_signal.state = :state THEN artifact_signal.state_changed_at ELSE :now END
        WHERE workspace_id = :#{#key.workspaceId()}
          AND artifact_kind = :#{#key.artifactKind().value()}
          AND artifact_id = :#{#key.artifactId()}
          AND signal_name = :#{#key.signalName().value()}
          AND revision = :#{#key.revision().value()}
          AND state IN ('RECORDED', 'DEFERRED', 'PENDING')
        """, nativeQuery = true)
    int markRefused(
            @Param("key") SignalKey key,
            @Param("state") String state,
            @Param("stateReason") String stateReason,
            @Param("now") Instant now);

    /**
     * Claims the batch before any of it is re-offered, which is what makes {@link #findRetryablePending}'s
     * ordering a queue rather than a treadmill: a re-offer that throws leaves the row untouched, so
     * without an up-front claim the same batch returns to the head of the very next sweep and
     * permanently-failing signals starve every other workspace.
     *
     * <p>Its own column rather than {@code state_changed_at}: claiming on the column the lapse deadline
     * measures would push that deadline out by one sweep every sweep, so nothing could ever reach it.
     *
     * @return how many rows were still {@code PENDING} to claim
     */
    @WorkspaceAgnostic("The reaper re-offers refused signals for every workspace on one instance")
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE artifact_signal SET last_attempted_at = :now
        WHERE id IN (:ids) AND state = 'PENDING'
        """, nativeQuery = true)
    int claimPendingForRetry(@Param("ids") List<UUID> ids, @Param("now") Instant now);

    /**
     * The id tiebreak is not cosmetic: a claim stamps a whole batch with one timestamp, so beyond one
     * batch of due rows the ordering key alone ties across all of them and the database may keep
     * returning the same rows.
     */
    @WorkspaceAgnostic("The reaper re-offers refused signals for every workspace on one instance")
    @Query("SELECT s FROM ArtifactSignal s JOIN FETCH s.workspace"
            + " WHERE s.state = de.tum.cit.aet.hephaestus.integration.core.signal.SignalState.PENDING"
            + " AND COALESCE(s.lastAttemptedAt, s.stateChangedAt) < :retryBefore"
            + " ORDER BY COALESCE(s.lastAttemptedAt, s.stateChangedAt) ASC, s.id ASC")
    List<ArtifactSignal> findRetryablePending(@Param("retryBefore") Instant retryBefore, Pageable pageable);

    /** @return how many signals lapsed */
    @WorkspaceAgnostic("The reaper re-offers refused signals for every workspace on one instance")
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE artifact_signal
        SET state = 'LAPSED', state_reason = 'PENDING_DEADLINE_EXCEEDED', state_changed_at = :now
        WHERE state = 'PENDING' AND state_changed_at < :deadline
        """, nativeQuery = true)
    int lapseStalePending(@Param("deadline") Instant deadline, @Param("now") Instant now);

    /**
     * This is also the tenancy check: the ledger row is the only workspace-scoped fact about a mirrored
     * artifact — a repository belongs to a workspace through a monitor mapping rather than a column — so
     * an id the caller does not own comes back empty here, and a 404 rather than a title.
     */
    @Query("SELECT s FROM ArtifactSignal s WHERE s.workspace.id = :workspaceId"
            + " AND s.artifactKind = :artifactKind AND s.artifactId = :artifactId"
            + " ORDER BY s.occurredAt ASC, s.signalName ASC")
    List<ArtifactSignal> findForArtifact(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") String artifactKind,
            @Param("artifactId") Long artifactId);

    /**
     * One row per artifact this workspace was in a position to say something about — including,
     * deliberately, the ones it said nothing about, which no job-derived listing can show.
     */
    @Query(
            value = "SELECT s.artifactKind AS artifactKind, s.artifactId AS artifactId,"
                    + " MAX(s.occurredAt) AS lastSignalAt, COUNT(s) AS signalCount,"
                    + " SUM(CASE WHEN s.state = de.tum.cit.aet.hephaestus.integration.core.signal.SignalState.TRIGGERED"
                    + " THEN 1 ELSE 0 END) AS reviewedSignalCount"
                    + " FROM ArtifactSignal s WHERE s.workspace.id = :workspaceId"
                    + " AND (:artifactKind IS NULL OR s.artifactKind = :artifactKind)"
                    + " GROUP BY s.artifactKind, s.artifactId ORDER BY MAX(s.occurredAt) DESC, s.artifactId DESC",
            countQuery =
                    "SELECT COUNT(DISTINCT CONCAT(s.artifactKind, ':', s.artifactId)) FROM ArtifactSignal s"
                            + " WHERE s.workspace.id = :workspaceId AND (:artifactKind IS NULL OR s.artifactKind = :artifactKind)")
    Page<SignalledArtifactRow> findSignalledArtifacts(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") @Nullable String artifactKind,
            Pageable pageable);

    /**
     * Evidence against a dormancy claim, which is otherwise derived from which integrations are
     * connected. That derivation answers "will this ever fire" and not "has this ever fired", so a signal
     * already in the ledger overrules it.
     *
     * <p>Narrowing to one kind loses no such refutation, and seeks the leading columns of
     * {@code uq_artifact_signal}: {@code artifact_kind} is written from the signal name's own prefix
     * ({@link SignalKey#artifactKind()}), so a signal a practice of this kind can watch is never recorded
     * under another kind.
     */
    @Query("SELECT DISTINCT s.signalName FROM ArtifactSignal s"
            + " WHERE s.workspace.id = :workspaceId AND s.artifactKind = :artifactKind")
    List<String> findRecordedSignalNames(
            @Param("workspaceId") Long workspaceId, @Param("artifactKind") String artifactKind);

    interface SignalledArtifactRow {
        String getArtifactKind();

        Long getArtifactId();

        Instant getLastSignalAt();

        long getSignalCount();

        long getReviewedSignalCount();
    }
}
