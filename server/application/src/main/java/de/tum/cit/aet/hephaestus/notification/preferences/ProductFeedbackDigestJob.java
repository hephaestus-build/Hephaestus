package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.ProductFeedbackDigestRequested;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("A bounded daily digest for opted-in instance administrators")
@RequiredArgsConstructor
public class ProductFeedbackDigestJob {
    private final NotificationSubscriptionRepository subscriptions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "product-feedback-digest", lockAtMostFor = "PT1M", lockAtLeastFor = "PT10S")
    @Transactional
    public void queue() {
        var now = clock.instant().atZone(ZoneOffset.UTC);
        if (now.getHour() < 8) return;
        var date = now.toLocalDate().minusDays(1);
        var until = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (var subscription : subscriptions.pendingDigests(date, PageRequest.of(0, 100))) {
            var since = subscription.getEnabledSince();
            var lastDate = subscription.getLastDigestDate();
            var from = lastDate == null
                    ? until.minus(Duration.ofDays(7))
                    : lastDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (from.isBefore(until.minus(Duration.ofDays(7)))) from = until.minus(Duration.ofDays(7));
            if (since != null && since.isAfter(from)) from = since;
            subscription.setLastDigestDate(date);
            if (since != null && from.isBefore(until)) {
                // Cursor and recipient publication commit together; SMTP happens only after commit.
                events.publishEvent(new ProductFeedbackDigestRequested(subscription.getAccountId(), from, until));
            }
        }
    }
}
