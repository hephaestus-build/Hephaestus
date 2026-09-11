package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkspaceAgnostic("Worker-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
@ConditionalOnProperty(
        prefix = "hephaestus.runtime.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class JobEvidenceCleanup {
    private final JobEvidenceFiles evidenceFiles;

    JobEvidenceCleanup(JobEvidenceFiles evidenceFiles) {
        this.evidenceFiles = evidenceFiles;
    }

    @Scheduled(fixedDelay = 60000)
    void cleanEndedAttempts() {
        evidenceFiles.cleanEndedAttempts();
    }
}
