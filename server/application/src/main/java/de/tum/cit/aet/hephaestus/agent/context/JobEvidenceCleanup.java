package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkspaceAgnostic("Worker-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
@ConditionalOnWorkerRole
class JobEvidenceCleanup {
    private final JobEvidenceFiles evidenceFiles;

    JobEvidenceCleanup(JobEvidenceFiles evidenceFiles) {
        this.evidenceFiles = evidenceFiles;
    }

    @EventListener(ApplicationReadyEvent.class)
    void cleanAfterRestart() {
        evidenceFiles.cleanAfterRestart();
    }

    @Scheduled(fixedDelay = 60000)
    void cleanEndedAttempts() {
        evidenceFiles.cleanEndedAttempts();
    }
}
