package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Maintenance of the server's evidence store, without enabling server-wide {@code @Scheduled} methods. Every server
 * holds the store, also with agent jobs off: person erasure waits for its acknowledgement.
 * Test and build profiles invoke maintenance explicitly.
 */
@Configuration(proxyBeanMethods = false)
@WorkspaceAgnostic("Server-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
@ConditionalOnServerRole
class JobEvidenceMaintenanceConfiguration {
    private final JobEvidenceFiles evidenceFiles;
    private final EvidenceFolderPersonDataCatalog personCopies;

    JobEvidenceMaintenanceConfiguration(JobEvidenceFiles evidenceFiles, EvidenceFolderPersonDataCatalog personCopies) {
        this.evidenceFiles = evidenceFiles;
        this.personCopies = personCopies;
    }

    @EventListener(ApplicationReadyEvent.class)
    void cleanAfterRestart() {
        evidenceFiles.cleanAfterRestart();
    }

    @Bean
    @Profile("!test & !specs & !cds-training")
    ScheduledTaskRegistrar evidenceErasureTasks() {
        var tasks = new ScheduledTaskRegistrar();
        // Person erasure waits for this acknowledgement; a long folder sweep must not hold its thread.
        tasks.addFixedDelayTask(
                new FixedDelayTask(personCopies::removeLocalRequests, Duration.ofSeconds(1), Duration.ZERO));
        return tasks;
    }

    @Bean
    @Profile("!test & !specs & !cds-training")
    ScheduledTaskRegistrar endedAttemptCleanupTasks() {
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(
                new FixedDelayTask(evidenceFiles::cleanEndedAttempts, Duration.ofMinutes(1), Duration.ZERO));
        return tasks;
    }
}
