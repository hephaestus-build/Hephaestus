package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/** Reuses the connection lease, heartbeat, cancellation and crash recovery of ordinary sync jobs. */
@Service
@ConditionalOnServerRole
public class DirectoryReconciliationService {
    private final DirectoryPolicyService policies;
    private final IntegrationSyncRunner runner;
    private final SyncJobService jobs;
    private final AsyncTaskExecutor executor;

    public DirectoryReconciliationService(
            DirectoryPolicyService policies,
            @Qualifier("directorySyncAdapter") IntegrationSyncRunner runner,
            SyncJobService jobs,
            @Qualifier("syncJobExecutor") AsyncTaskExecutor executor) {
        this.policies = policies;
        this.runner = runner;
        this.jobs = jobs;
        this.executor = executor;
    }

    private IntegrationKind kind() {
        return IntegrationKind.KEYCLOAK_DIRECTORY;
    }

    public SyncJobDTO request(long workspaceId, boolean preview) {
        long connectionId = policies.connectionForJob(workspaceId, preview);
        return dispatch(new SyncJobRequest(
                workspaceId,
                connectionId,
                kind(),
                preview ? SyncJobType.INITIAL : SyncJobType.RECONCILIATION,
                SyncJobTrigger.MANUAL,
                SecurityUtils.getCurrentAccountId().orElse(null)));
    }

    public void scheduled(long workspaceId) {
        long connectionId = policies.scheduledConnection(workspaceId);
        dispatch(new SyncJobRequest(
                workspaceId, connectionId, kind(), SyncJobType.RECONCILIATION, SyncJobTrigger.SCHEDULED, null));
    }

    private SyncJobDTO dispatch(SyncJobRequest request) {
        SyncJobService.Started started;
        try {
            started = jobs.beginJob(request);
        } catch (SyncJobConflictException conflict) {
            if (conflict.activeJob().getType() != request.type())
                throw new SyncStateConflictException(
                        "Wait for the current directory job before starting a different pass",
                        Map.of("conflictingJobId", conflict.activeJob().getId()),
                        conflict);
            return SyncJobDTO.from(conflict.activeJob());
        }
        var ref = new IntegrationRef(kind(), request.workspaceId(), null, request.connectionId());
        try {
            executor.execute(() -> jobs.executeBody(started, handle -> runner.reconcile(ref, handle, request.type())));
        } catch (TaskRejectedException rejected) {
            jobs.failStarted(started, "Directory dispatch rejected; retry when the executor is available");
            throw rejected;
        }
        return SyncJobDTO.from(started.job());
    }
}
