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
            write_started, next_attempt_at, attempt_count, created_at, updated_at
        ) SELECT
            :#{#command.id()}, :#{#command.destinationKey()}, :#{#command.workspaceId()}, :#{#command.agentJobId()}, :#{#command.feedbackId()}, :#{#command.destination()}, 'PENDING', :#{#command.body()},
            CAST(:#{#command.practiceSlugs()} AS jsonb),
            CAST(:#{#command.packageContent()} AS jsonb), '[]'::jsonb,
            FALSE, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
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
           AND (attempt_count < :maxAttempts OR write_started = TRUE)
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET write_started = TRUE, updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND state = 'CLAIMED'
           AND lease_owner = :owner AND write_started = FALSE
           AND lease_expires_at > CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int beginWrite(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    /** Reopens the fence this lease closed, once its channel proved the create request was never sent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET write_started = FALSE, updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND workspace_id = :workspaceId AND state = 'CLAIMED'
           AND lease_owner = :owner AND write_started = TRUE
           AND lease_expires_at > CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int releaseUnsentWrite(@Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("owner") String owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_dispatch SET state = :#{#completion.state()}, delivered_external_ref = :#{#completion.externalRef()},
                delivered_external_url = :#{#completion.externalUrl()},
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
          AND (d.attemptCount < :maxAttempts OR d.writeStarted = true)
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
