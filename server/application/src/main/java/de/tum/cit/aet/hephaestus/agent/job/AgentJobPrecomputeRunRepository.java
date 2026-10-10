package de.tum.cit.aet.hephaestus.agent.job;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentJobPrecomputeRunRepository
        extends JpaRepository<AgentJobPrecomputeRun, AgentJobPrecomputeRun.Id> {

    /** Every attempt's runs of these jobs; a job of another workspace has none. */
    @Query("SELECT r FROM AgentJobPrecomputeRun r WHERE r.workspace.id = :workspaceId AND r.id.jobId IN :jobIds")
    List<AgentJobPrecomputeRun> findByWorkspaceIdAndJobIdIn(
            @Param("workspaceId") long workspaceId, @Param("jobIds") Collection<UUID> jobIds);

    /** The runs of one attempt of the job. Only the attempt's terminal write records them. */
    @Query("""
            SELECT r FROM AgentJobPrecomputeRun r
            WHERE r.workspace.id = :workspaceId AND r.id.jobId = :jobId AND r.id.attempt = :attempt
            """)
    List<AgentJobPrecomputeRun> findByWorkspaceIdAndJobIdAndAttempt(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    /** What the proxy counted for the precompute calls of one attempt of the job, by practice and model kind. */
    @Query("""
            SELECT u FROM AgentJobPrecomputeUsage u
            WHERE u.workspace.id = :workspaceId AND u.id.jobId = :jobId AND u.id.attempt = :attempt
            """)
    List<AgentJobPrecomputeUsage> findUsageByWorkspaceIdAndJobIdAndAttempt(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    /** The current names of the workspace's practices with these slugs. */
    @Query(value = """
                    SELECT p.slug AS "slug", p.name AS "name"
                    FROM practice p
                    WHERE p.workspace_id = :workspaceId
                      AND p.slug IN (:slugs)
                    """, nativeQuery = true)
    List<PracticeNameRow> findPracticeNamesByWorkspaceId(
            @Param("workspaceId") long workspaceId, @Param("slugs") Collection<String> slugs);

    interface PracticeNameRow {
        String getSlug();

        String getName();
    }

    /** Of these practice revisions of the workspace, the ones that hold a precompute script. */
    @Query(value = """
                    SELECT r.id
                    FROM practice_revision r
                    JOIN practice p ON p.id = r.practice_id
                    WHERE p.workspace_id = :workspaceId
                      AND r.id IN (:revisionIds)
                      AND r.precompute_script IS NOT NULL
                    """, nativeQuery = true)
    Set<Long> findPrecomputeRevisionIdsByWorkspaceId(
            @Param("workspaceId") long workspaceId, @Param("revisionIds") Collection<Long> revisionIds);

    /**
     * One row per practice of the workspace that has a precompute script, with its newest run that reported
     * the script's models. A run whose script did not finish, or ended before it declared its models, holds no
     * models, so it is passed over: it can say nothing about them. The run is current when its admitted revision
     * held the script the practice holds now.
     *
     * <p>A lateral probe per practice uses the {@code (workspace_id, practice_slug, finished_at)} index;
     * {@code DISTINCT ON} cannot skip-scan it.
     */
    @Query(value = """
                    SELECT p.slug AS "practiceSlug", p.name AS "practiceName", run.job_id AS "jobId",
                           run.finished_at AS "finishedAt", CAST(run.models AS text) AS "models",
                           (rev.precompute_script IS NOT DISTINCT FROM p.precompute_script) AS "current"
                    FROM practice p
                    LEFT JOIN LATERAL (
                        SELECT r.job_id, r.finished_at, r.models, r.practice_revision_id
                        FROM agent_job_precompute_run r
                        WHERE r.workspace_id = :workspaceId
                          AND r.practice_slug = p.slug
                          AND r.models IS NOT NULL
                        ORDER BY r.finished_at DESC, r.attempt DESC
                        LIMIT 1
                    ) run ON TRUE
                    LEFT JOIN practice_revision rev ON rev.id = run.practice_revision_id AND rev.practice_id = p.id
                    WHERE p.workspace_id = :workspaceId
                      AND p.precompute_script IS NOT NULL
                    ORDER BY p.name, p.slug
                    """, nativeQuery = true)
    List<LatestRunRow> findLatestRunPerPracticeByWorkspaceId(@Param("workspaceId") long workspaceId);

    interface LatestRunRow {
        String getPracticeSlug();

        String getPracticeName();

        /** Null when no run of the practice reported its models. */
        @Nullable
        UUID getJobId();

        @Nullable
        Instant getFinishedAt();

        /** The run's models as stored JSON. */
        @Nullable
        String getModels();

        /** Whether the run's revision held today's script; false when there is no run. */
        boolean getCurrent();
    }
}
