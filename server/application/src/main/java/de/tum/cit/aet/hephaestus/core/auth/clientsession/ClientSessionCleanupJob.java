package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link ClientSessionPruner} hourly on one server pod at a time. */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Installed-client sessions are account-scoped, not workspace-scoped")
public class ClientSessionCleanupJob {

    private final ClientSessionPruner pruner;

    public ClientSessionCleanupJob(ClientSessionPruner pruner) {
        this.pruner = pruner;
    }

    /** Handoffs live 60 seconds and ended sessions a day, so an hourly pass keeps both small. */
    @Scheduled(cron = "0 15 * * * *")
    @SchedulerLock(name = "client-session-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        pruner.prune();
    }
}
