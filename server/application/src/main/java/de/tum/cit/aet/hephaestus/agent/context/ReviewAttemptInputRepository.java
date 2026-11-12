package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Attempt-scoped access only: no listing, so no queue or admin read can carry a file. */
public interface ReviewAttemptInputRepository extends Repository<ReviewAttemptInput, UUID> {

    /** One recorded file of an attempt's folder, without its content. */
    interface File {
        String getPath();

        String getSha256();

        String getPermissions();
    }

    /** Records one file for the job's running attempt; matches nothing once another attempt runs. */
    @Modifying
    @Query(
            value =
                    "INSERT INTO review_attempt_input(id,workspace_id,job_id,attempt,run_id,path,sha256,permissions,content)"
                            + " SELECT gen_random_uuid(),j.workspace_id,j.id,:attempt,j.run_id,:#{#input.path()},:#{#input.sha256()},:#{#input.permissions()},:content"
                            + " FROM review_job j JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id"
                            + " WHERE j.id=:jobId AND j.workspace_id=:workspaceId AND j.status='RUNNING'"
                            + " AND " + ReviewRunKind.ATTEMPT_OF_RUN + "=:attempt",
            nativeQuery = true)
    int record(
            @Param("workspaceId") long workspaceId,
            @Param("jobId") UUID jobId,
            @Param("attempt") int attempt,
            @Param("input") AttemptInputs.RecordedFile input,
            @Param("content") byte[] content);

    @Query(value = """
        SELECT path, sha256, permissions FROM review_attempt_input
        WHERE workspace_id=:workspaceId AND job_id=:jobId AND attempt=:attempt AND run_id=:runId ORDER BY path
        """, nativeQuery = true)
    List<File> findFiles(
            @Param("workspaceId") long workspaceId,
            @Param("jobId") UUID jobId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId);

    @Query(value = """
        SELECT content FROM review_attempt_input
        WHERE workspace_id=:workspaceId AND job_id=:jobId AND attempt=:attempt AND run_id=:runId AND path=:path
        """, nativeQuery = true)
    byte @Nullable [] findContent(
            @Param("workspaceId") long workspaceId,
            @Param("jobId") UUID jobId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId,
            @Param("path") String path);

    /** Deletes the rows of every attempt that is not its job's running attempt. */
    @WorkspaceAgnostic("Cross-workspace reconcile; each row is compared with its own job in its own workspace")
    @Modifying
    @Query(
            value = "DELETE FROM review_attempt_input i WHERE NOT EXISTS("
                    + "SELECT 1 FROM review_job j JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id"
                    + " WHERE j.id=i.job_id AND j.workspace_id=i.workspace_id AND j.status='RUNNING' AND j.run_id=i.run_id"
                    + " AND " + ReviewRunKind.ATTEMPT_OF_RUN + "=i.attempt)",
            nativeQuery = true)
    int deleteEnded();
}
