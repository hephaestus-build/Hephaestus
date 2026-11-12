package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceErasureNotifications;
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
 * Maintenance of the server's evidence folders, without enabling server-wide {@code @Scheduled} methods. Every server
 * runs it, also with agent jobs off: a server holds evidence folders whenever it captured or rebuilt one.
 * Test and build profiles invoke maintenance explicitly.
 */
@Configuration(proxyBeanMethods = false)
@WorkspaceAgnostic("Server-local cleanup rechecks each workspace-scoped job and attempt owner before removal")
@ConditionalOnServerRole
class JobEvidenceMaintenanceConfiguration {
    private final JobEvidenceFiles evidenceFiles;
    private final EvidenceReconciler reconciler;

    JobEvidenceMaintenanceConfiguration(JobEvidenceFiles evidenceFiles, EvidenceReconciler reconciler) {
        this.evidenceFiles = evidenceFiles;
        this.reconciler = reconciler;
    }

    @EventListener(ApplicationReadyEvent.class)
    void cleanAfterRestart() {
        evidenceFiles.cleanAfterRestart();
    }

    @Bean
    @Profile("!test & !specs & !cds-training")
    ScheduledTaskRegistrar evidenceReconcileTasks(EvidenceErasureNotifications erasures) {
        erasures.subscribe(reconciler::reconcile);
        var tasks = new ScheduledTaskRegistrar();
        tasks.addFixedDelayTask(new FixedDelayTask(reconciler::reconcile, EvidenceReconciler.INTERVAL, Duration.ZERO));
        return tasks;
    }
}
