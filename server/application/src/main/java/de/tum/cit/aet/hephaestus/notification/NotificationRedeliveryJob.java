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
 * account exports. A publication is retried after a backoff from its last attempt, and after {@link #MAX_ATTEMPTS}
 * attempts in all it is exhausted: it stays in the registry as the record of the failure, and no resubmission picks it
 * again. Both rules are part of the selection's SQL, before its batch limit, so neither a waiting nor an exhausted row
 * can starve later publications, and servers that take turns share one backoff (ADR 0044).
 */
@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("Redelivery sweeps the instance-wide registry; every listener scopes its own work")
public class NotificationRedeliveryJob {

    static final Duration MIN_AGE = Duration.ofMinutes(5);
    static final int BATCH_SIZE = 50;

    /**
     * The attempts in all, the first delivery included, after which a failed publication is exhausted, as for an
     * integration event. With the backoff between them, ten attempts span about six hours.
     */
    public static final int MAX_ATTEMPTS = 10;

    /** The wait after the first failed attempt; it doubles with each further attempt, up to {@link #MAX_BACKOFF}. */
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);

    static final Duration MAX_BACKOFF = Duration.ofHours(1);

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
