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
 * Resubmits failed event publications of every module: notifications, and other durable work such as
 * account exports. A publication that failed {@link #MAX_ATTEMPTS} times is exhausted: it stays in the registry as the
 * record of the failure, and no resubmission picks it again. The limit is part of the selection's SQL, before its batch
 * limit, so exhausted rows cannot starve later publications (ADR 0044).
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("Redelivery sweeps the instance-wide registry; every listener scopes its own work")
public class NotificationRedeliveryJob {

    static final Duration MIN_AGE = Duration.ofMinutes(5);
    static final int BATCH_SIZE = 50;

    /**
     * The attempts after which a failed publication is exhausted, as for an integration event. A failure that repeats
     * ten times over at least 45 minutes is the listener's, not an outage's.
     */
    public static final int MAX_ATTEMPTS = 10;

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
