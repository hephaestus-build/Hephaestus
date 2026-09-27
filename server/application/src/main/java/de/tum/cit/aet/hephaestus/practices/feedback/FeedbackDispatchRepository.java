package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Tenant-scoped writes carry workspace_id; the recovery queries deliberately scan the fleet")
public interface FeedbackDispatchRepository extends JpaRepository<FeedbackDispatch, UUID> {
    Optional<FeedbackDispatch> findByDestinationKeyAndWorkspaceId(String destinationKey, Long workspaceId);

    Optional<FeedbackDispatch> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    @Modifying
    @Query(value = """
        INSERT INTO feedback_dispatch (
            id, destination_key, workspace_id, agent_job_id, feedback_id, destination, state, body,
            practice_slugs, package_content, delivered_placements,
            write_started, inline_write_started, next_attempt_at, attempt_count, created_at, updated_at
        ) SELECT
            :#{#command.id()}, :#{#command.destinationKey()}, :#{#command.workspaceId()}, :#{#command.agentJobId()}, :#{#command.feedbackId()}, :#{#command.destination()}, 'PENDING', :#{#command.body()},
            CAST(:#{#command.practiceSlugs()} AS jsonb),
            CAST(:#{#command.packageContent()} AS jsonb), '[]'::jsonb,
            FALSE, FALSE, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
          FROM agent_job j
         WHERE j.id = :#{#command.agentJobId()} AND j.workspace_id = :#{#command.workspaceId()}
        ON CONFLICT (destination_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("command") FeedbackDispatchInsert command);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch
           SET state = 'CLAIMED', lease_owner = :owner, lease_expires_at = :leaseUntil,
               attempt_count = attempt_count + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId
           AND (attempt_count < :maxAttempts OR write_started = TRUE OR inline_write_started IS DISTINCT FROM FALSE)
           AND (state IN ('PENDING', 'UNCERTAIN')
                OR (state = 'CLAIMED' AND lease_expires_at < CURRENT_TIMESTAMP))
           AND next_attempt_at <= CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int claim(
            @Param("id") UUID id,
            @Param("workspaceId") Long workspaceId,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("maxAttempts") int maxAttempts);

    /**
     * Whether dispatch {@code d} cites observation {@code o}, the one rule every read below shares. An approved
     * package cites the observations bound to its feedback. An automatic package cites those its persisted package
     * names as contributors, or its whole run if it was persisted before contributors were recorded. Once
     * projection has cleared a sent or withheld package, the ledger it wrote first answers instead: the delivered
     * unit (position 0) and the withheld remainder (position 5000) bind exactly what the package was written from.
     */
    String CITES = """
        (CASE
            WHEN d.feedback_id IS NOT NULL THEN EXISTS (
                SELECT 1 FROM feedback_observation fo
                WHERE fo.feedback_id = d.feedback_id AND fo.observation_id = o.id)
            WHEN o.agent_job_id <> d.agent_job_id THEN FALSE
            WHEN d.projected_at IS NOT NULL AND d.state IN ('SENT', 'SUPPRESSED') THEN EXISTS (
                SELECT 1 FROM feedback f
                JOIN feedback_observation fo ON fo.feedback_id = f.id
                WHERE f.workspace_id = d.workspace_id AND f.agent_job_id = d.agent_job_id
                  AND f.channel = 'IN_CONTEXT' AND f.position IN (0, 5000) AND fo.observation_id = o.id)
            WHEN jsonb_typeof(d.package_content -> 'summaryContributors') = 'array' THEN
                (d.package_content -> 'summaryContributors') @> jsonb_build_array(o.occurrence_key)
                OR EXISTS (
                    SELECT 1 FROM jsonb_array_elements(d.package_content -> 'diffNotes') note
                    WHERE (note -> 'contributors') @> jsonb_build_array(o.occurrence_key))
            ELSE TRUE
        END)
        """;

    /**
     * Whether an inline note of dispatch {@code d} may have been requested: recorded as begun, or unknown on a
     * dispatch recorded before that was tracked unless its package names no inline notes at all. A cleared or
     * missing package proves nothing.
     */
    String INLINE_WRITE_MAY_HAVE_STARTED = """
        (d.inline_write_started IS TRUE
         OR (d.inline_write_started IS NULL AND d.package_content -> 'diffNotes' IS DISTINCT FROM '[]'::jsonb))
        """;

    /**
     * Share-locks the observations this dispatch cites. A correction holds its observation {@code FOR UPDATE} while
     * it checks for a delivery in progress, so an attempt admitted here either sees that correction or is seen by it.
     */
    @Query(value = """
        SELECT o.id FROM observation o
        JOIN feedback_dispatch d ON d.id = :dispatchId AND d.workspace_id = o.workspace_id
        WHERE o.workspace_id = :workspaceId AND
        """ + CITES + """
        FOR SHARE OF o
        """, nativeQuery = true)
    List<UUID> lockCitedObservations(@Param("workspaceId") Long workspaceId, @Param("dispatchId") UUID dispatchId);

    /** Whether a delivery citing this observation holds a live claim, so it may be talking to the provider now. */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM feedback_dispatch d
            JOIN observation o ON o.id = :observationId AND o.workspace_id = d.workspace_id
            WHERE d.workspace_id = :workspaceId AND d.state = 'CLAIMED' AND d.lease_expires_at > CURRENT_TIMESTAMP
              AND
        """ + CITES + """
        )
        """, nativeQuery = true)
    boolean existsInFlightCiting(@Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    /**
     * Whether a dispatch citing this observation ended withheld or failed without accounting for a write an earlier
     * attempt may have begun. Only a withholding for an invalidated observation reconciles those first, so it alone
     * is trusted.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM feedback_dispatch d
            JOIN observation o ON o.id = :observationId AND o.workspace_id = d.workspace_id
            WHERE d.workspace_id = :workspaceId AND d.state IN ('SUPPRESSED', 'FAILED')
              AND COALESCE(d.suppression_reason, '') <> 'OBSERVATION_INVALIDATED'
              AND (d.write_started OR
        """ + INLINE_WRITE_MAY_HAVE_STARTED + """
              )
              AND
        """ + CITES + """
        )
        """, nativeQuery = true)
    boolean existsUnconfirmedCiting(@Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    /** When the earliest still-unsettled dispatch citing this observation began a write it has not confirmed. */
    @Query(value = """
        SELECT MIN(COALESCE(d.write_started_at, d.created_at)) FROM feedback_dispatch d
        JOIN observation o ON o.id = :observationId AND o.workspace_id = d.workspace_id
        WHERE d.workspace_id = :workspaceId AND d.projected_at IS NULL
          AND (d.write_started OR
        """ + INLINE_WRITE_MAY_HAVE_STARTED + """
          )
          AND
        """ + CITES, nativeQuery = true)
    @Nullable
    Instant findUnconfirmedWriteSince(
            @Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    /** Whether a dispatch citing this observation is still unsettled, so what it posted is not yet in the ledger. */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM feedback_dispatch d
            JOIN observation o ON o.id = :observationId AND o.workspace_id = d.workspace_id
            WHERE d.workspace_id = :workspaceId AND d.projected_at IS NULL
              AND
        """ + CITES + """
        )
        """, nativeQuery = true)
    boolean existsUnsettledCiting(@Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET write_started = TRUE, write_started_at = CURRENT_TIMESTAMP,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND state = 'CLAIMED'
           AND lease_owner = :owner AND write_started = FALSE
           AND lease_expires_at > CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int beginWrite(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    /** Records, before the first inline request leaves, that this lease is about to write inline notes. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET inline_write_started = TRUE,
               write_started_at = COALESCE(write_started_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND state = 'CLAIMED'
           AND lease_owner = :owner AND lease_expires_at > CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int beginInlineWrite(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    /** Reopens the fence this lease closed, once its channel proved the create request was never sent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET write_started = FALSE, write_started_at = NULL, updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND state = 'CLAIMED'
           AND lease_owner = :owner AND write_started = TRUE
           AND lease_expires_at > CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int releaseUnsentWrite(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET state = :#{#completion.state()}, delivered_external_ref = :#{#completion.externalRef()},
               lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = :#{#completion.nextAttemptAt()},
               last_error = :#{#completion.error()}, suppression_reason = :#{#completion.suppressionReason()},
               delivered_placements = CAST(:#{#completion.deliveredPlacements()} AS jsonb),
               updated_at = CURRENT_TIMESTAMP
         WHERE id = :#{#completion.id()} AND workspace_id = :#{#completion.workspaceId()} AND state = 'CLAIMED' AND lease_owner = :#{#completion.owner()}
        """, nativeQuery = true)
    int finish(@Param("completion") FeedbackDispatchCompletion completion);

    @Query("""
        SELECT d FROM FeedbackDispatch d
        WHERE d.nextAttemptAt <= :now
          AND (d.attemptCount < :maxAttempts OR d.writeStarted = true
               OR d.inlineWriteStarted IS NULL OR d.inlineWriteStarted = true)
          AND (d.state IN (de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.PENDING,
                           de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.UNCERTAIN)
               OR (d.state = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.CLAIMED
                   AND d.leaseExpiresAt < :now))
        ORDER BY d.updatedAt ASC
        """)
    List<FeedbackDispatch> findRecoverable(
            @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Query("""
        SELECT d FROM FeedbackDispatch d
        WHERE d.attemptCount >= :maxAttempts
          AND d.writeStarted = false
          AND d.inlineWriteStarted = false
          AND (d.state IN (de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.PENDING,
                           de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.UNCERTAIN)
            OR (d.state = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.CLAIMED
                AND d.leaseExpiresAt < :now))
        ORDER BY d.updatedAt ASC
        """)
    List<FeedbackDispatch> findExhausted(
            @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable pageable);

    @Query("""
        SELECT d FROM FeedbackDispatch d
        WHERE d.projectedAt IS NULL
          AND (d.projectionOwner IS NULL OR d.projectionExpiresAt < :now)
          AND d.state IN (de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.SENT,
                          de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.SUPPRESSED,
                          de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState.FAILED)
        ORDER BY d.updatedAt ASC
        """)
    List<FeedbackDispatch> findUnprojectedTerminal(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch
           SET projection_owner = :owner, projection_expires_at = :leaseUntil, updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND projected_at IS NULL
           AND state IN ('SENT', 'SUPPRESSED', 'FAILED')
           AND (projection_owner IS NULL OR projection_expires_at < CURRENT_TIMESTAMP)
        """, nativeQuery = true)
    int claimProjection(
            @Param("id") UUID id,
            @Param("workspaceId") Long workspaceId,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch
           SET projection_owner = :owner, projection_expires_at = :leaseUntil, updated_at = CURRENT_TIMESTAMP
         WHERE destination_key = :destinationKey AND workspace_id = :workspaceId AND projected_at IS NULL
           AND state IN ('SENT', 'SUPPRESSED', 'FAILED')
           AND (projection_owner IS NULL OR projection_expires_at < CURRENT_TIMESTAMP)
        """, nativeQuery = true)
    int claimProjectionByKey(
            @Param("destinationKey") String destinationKey,
            @Param("workspaceId") Long workspaceId,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE feedback_dispatch SET projected_at = CURRENT_TIMESTAMP, projection_owner = NULL, "
                    + "projection_expires_at = NULL, "
                    + "body = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '' ELSE body END, "
                    + "practice_slugs = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '[]'::jsonb ELSE practice_slugs END, "
                    + "package_content = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '{}'::jsonb ELSE package_content END, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND projected_at IS NULL "
                    + "AND projection_owner = :owner AND state IN ('SENT', 'SUPPRESSED', 'FAILED')",
            nativeQuery = true)
    int markProjected(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE feedback_dispatch SET projected_at = CURRENT_TIMESTAMP, projection_owner = NULL, "
                    + "projection_expires_at = NULL, "
                    + "body = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '' ELSE body END, "
                    + "practice_slugs = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '[]'::jsonb ELSE practice_slugs END, "
                    + "package_content = CASE WHEN state IN ('SENT', 'SUPPRESSED') THEN '{}'::jsonb ELSE package_content END, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "WHERE destination_key = :destinationKey AND workspace_id = :workspaceId AND projected_at IS NULL "
                    + "AND projection_owner = :owner AND state IN ('SENT', 'SUPPRESSED', 'FAILED')",
            nativeQuery = true)
    int markProjectedByKey(
            @Param("destinationKey") String destinationKey,
            @Param("workspaceId") Long workspaceId,
            @Param("owner") String owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch
           SET state = 'FAILED', lease_owner = NULL, lease_expires_at = NULL,
               last_error = :error, updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId
           AND state NOT IN ('SENT', 'SUPPRESSED', 'FAILED')
        """, nativeQuery = true)
    int fail(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("error") @Nullable String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch
           SET state = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
               lease_owner = NULL, lease_expires_at = NULL, last_error = NULL,
               projected_at = NULL, projection_owner = NULL, projection_expires_at = NULL,
               updated_at = CURRENT_TIMESTAMP
         WHERE agent_job_id = :jobId AND workspace_id = :workspaceId
           AND destination = 'AUTOMATIC_REVIEW_PACKAGE' AND state = 'FAILED'
        """, nativeQuery = true)
    int resetFailedAutomaticPackage(@Param("jobId") UUID jobId, @Param("workspaceId") Long workspaceId);

    @Modifying
    @Query(value = """
        DELETE FROM feedback_dispatch dispatch
        USING agent_job job
        WHERE dispatch.workspace_id = :workspaceId
          AND job.workspace_id = dispatch.workspace_id
          AND job.id = dispatch.agent_job_id
          AND job.artifact_kind IN ('scm.pull_request', 'scm.issue')
        """, nativeQuery = true)
    int deleteScmArtifactDispatches(@Param("workspaceId") long workspaceId);

    @Modifying
    @Query("DELETE FROM FeedbackDispatch dispatch WHERE dispatch.workspaceId = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") long workspaceId);
}
