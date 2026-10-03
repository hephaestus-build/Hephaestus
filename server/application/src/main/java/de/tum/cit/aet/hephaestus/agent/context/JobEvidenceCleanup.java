package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    private final EvidenceFolderPersonDataCatalog personCopies;

    JobEvidenceCleanup(JobEvidenceFiles evidenceFiles, EvidenceFolderPersonDataCatalog personCopies) {
        this.evidenceFiles = evidenceFiles;
        this.personCopies = personCopies;
    }

    @EventListener(ApplicationReadyEvent.class)
    void cleanAfterRestart() {
        evidenceFiles.cleanAfterRestart();
    }

    @Scheduled(fixedDelay = 1000)
    void eraseRequestedCopies() {
        personCopies.removeLocalRequests();
    }

    @Scheduled(fixedDelay = 60000)
    void cleanEndedAttempts() {
        evidenceFiles.cleanEndedAttempts();
    }
}
