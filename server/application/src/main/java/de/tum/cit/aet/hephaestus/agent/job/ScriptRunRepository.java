package de.tum.cit.aet.hephaestus.agent.job;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
}
