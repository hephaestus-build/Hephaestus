package de.tum.cit.aet.hephaestus.agent.job;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ScriptUsageRepository extends Repository<ScriptUsage, ScriptUsage.Id> {
    @Query("SELECT u FROM ScriptUsage u WHERE u.workspaceId = :workspaceId AND u.id.jobId IN :jobIds")
    List<ScriptUsage> findByWorkspaceIdAndJobIdIn(
            @Param("workspaceId") long workspaceId, @Param("jobIds") Collection<UUID> jobIds);

    @Query("SELECT u FROM ScriptUsage u WHERE u.workspaceId = :workspaceId AND u.id.jobId = :jobId"
            + " ORDER BY u.id.attempt, u.id.modelKind, u.id.practiceId")
    List<ScriptUsage> findForJob(@Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId);
}
