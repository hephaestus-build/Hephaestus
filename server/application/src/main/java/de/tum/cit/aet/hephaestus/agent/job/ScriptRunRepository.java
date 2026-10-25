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
public interface ScriptRunRepository extends JpaRepository<ScriptRun, ScriptRun.Id> {
    @Query("SELECT r FROM ScriptRun r WHERE r.workspaceId = :workspaceId AND r.id.jobId IN :jobIds")
    List<ScriptRun> findByWorkspaceIdAndJobIdIn(
            @Param("workspaceId") long workspaceId, @Param("jobIds") Collection<UUID> jobIds);

    /** The runs of one attempt of the job. Only the attempt's terminal write records them. */
    @Query("""
        SELECT r FROM ScriptRun r
        WHERE r.workspaceId = :workspaceId AND r.id.jobId = :jobId AND r.id.attempt = :attempt
        """)
    List<ScriptRun> findByWorkspaceIdAndJobIdAndAttempt(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    @Query("""
        SELECT u FROM ScriptUsage u
        WHERE u.workspaceId = :workspaceId AND u.id.jobId = :jobId AND u.id.attempt = :attempt
        """)
    List<ScriptUsage> findUsageByWorkspaceIdAndJobIdAndAttempt(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    @Query(value = """
        SELECT p.id AS "id", p.slug AS "slug", v.name AS "name"
        FROM practice p
        JOIN practice_version v ON v.id = p.current_version_id AND v.workspace_id = p.workspace_id
        WHERE p.workspace_id = :workspaceId
          AND p.slug IN (:slugs)
        """, nativeQuery = true)
    List<PracticeNameRow> findPracticeNamesByWorkspaceId(
            @Param("workspaceId") long workspaceId, @Param("slugs") Collection<String> slugs);

    interface PracticeNameRow {
        String getSlug();

        String getName();
    }

    @Query(value = """
        SELECT r.id
        FROM practice_version r
        JOIN practice p ON p.id = r.practice_id
        WHERE p.workspace_id = :workspaceId
          AND r.id IN (:versionIds)
          AND r.definition ->> 'script' IS NOT NULL
        """, nativeQuery = true)
    Set<Long> findPracticeScriptVersionIdsByWorkspaceId(
            @Param("workspaceId") long workspaceId, @Param("versionIds") Collection<Long> versionIds);

    /**
     * One row per practice of the workspace that has a practice script, with its newest run that reported
     * the script's models. A run whose script did not finish, or ended before it declared its models, holds no
     * models, so it is passed over: it can say nothing about them. The run is current when its admitted practice version
     * held the script the practice holds now.
     *
     * <p>A lateral probe per practice uses the {@code (workspace_id, practice_slug, finished_at)} index;
     * {@code DISTINCT ON} cannot skip-scan it.
     */
    @Query(value = """
        SELECT p.slug AS "practiceSlug", current_version.name AS "practiceName", run.job_id AS "jobId", job.review_id AS "reviewId",
               run.finished_at AS "finishedAt", CAST(run.models AS text) AS "models",
               (rev.definition ->> 'script' IS NOT DISTINCT FROM current_version.definition ->> 'script') AS "current"
        FROM practice p
        JOIN practice_version current_version ON current_version.id = p.current_version_id AND current_version.workspace_id = p.workspace_id
        LEFT JOIN LATERAL (
            SELECT r.job_id, r.finished_at, r.models, r.practice_version_id
            FROM review_job_script_run r
            WHERE r.workspace_id = :workspaceId
              AND r.practice_id = p.id
              AND r.models IS NOT NULL
              AND r.script_kind = 'CUSTOM'
            ORDER BY r.finished_at DESC, r.attempt DESC
            LIMIT 1
        ) run ON TRUE
        LEFT JOIN review_job job ON job.id = run.job_id AND job.workspace_id = p.workspace_id
        LEFT JOIN practice_version rev ON rev.id = run.practice_version_id AND rev.practice_id = p.id
        WHERE p.workspace_id = :workspaceId
          AND current_version.definition ->> 'script' IS NOT NULL
        ORDER BY current_version.name, p.slug
        """, nativeQuery = true)
    List<LatestRunRow> findLatestRunPerPracticeByWorkspaceId(@Param("workspaceId") long workspaceId);

    interface LatestRunRow {
        String getPracticeSlug();

        String getPracticeName();

        /** Null when no run of the practice reported its models. */
        @Nullable
        UUID getJobId();

        @Nullable
        UUID getReviewId();

        @Nullable
        Instant getFinishedAt();

        @Nullable
        String getModels();

        boolean getCurrent();
    }
}
