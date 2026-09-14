package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resubmits failed notifications. Expiration belongs to listeners: filtering after Modulith's SQL
 * batch limit would let expired rows starve later publications (ADR 0044).
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("Redelivery sweeps the instance-wide registry; every listener scopes its own work")
public class NotificationRedeliveryJob {

    static final Duration MIN_AGE = Duration.ofMinutes(5);
    static final int BATCH_SIZE = 50;

    private final FailedEventPublications failedPublications;

    public NotificationRedeliveryJob(FailedEventPublications failedPublications) {
        this.failedPublications = failedPublications;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @SchedulerLock(name = "notification-redelivery", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void resubmitFailed() {
        failedPublications.resubmit(ResubmissionOptions.defaults()
                .withMinAge(MIN_AGE)
                .withBatchSize(BATCH_SIZE)
                .withMaxInFlight(BATCH_SIZE));
    }
}
