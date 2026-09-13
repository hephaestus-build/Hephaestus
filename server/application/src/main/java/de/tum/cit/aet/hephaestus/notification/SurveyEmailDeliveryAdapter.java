package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.SurveyEmailDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Survey email subscriptions belong to native accounts")
class SurveyEmailDeliveryAdapter implements SurveyEmailDelivery {
    private final NotificationSubscriptionService subscriptions;
    private final AccountContactQuery contacts;
    private final ApplicationEventPublisher events;

    @Override
    public List<Long> subscribedAccountIds(boolean research) {
        return subscriptions
                .subscribedAccountIds(
                        research
                                ? NotificationSubscriptionKind.RESEARCH_SURVEYS
                                : NotificationSubscriptionKind.PRODUCT_SURVEYS)
                .stream()
                .filter(id -> contacts.activeVerifiedPrimaryEmail(id).isPresent())
                .toList();
    }

    @Override
    public boolean isSubscribed(long accountId, boolean research) {
        return subscriptions.isEnabled(
                        accountId,
                        research
                                ? NotificationSubscriptionKind.RESEARCH_SURVEYS
                                : NotificationSubscriptionKind.PRODUCT_SURVEYS)
                && contacts.activeVerifiedPrimaryEmail(accountId).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(UUID surveyId, long accountId, Instant expiresAt, boolean reminder, long generation) {
        events.publishEvent(new SurveyEmailRequested(surveyId, accountId, expiresAt, reminder, generation));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestSummary(UUID surveyId, Instant endedAt, Instant expiresAt) {
        var subscribed = new java.util.HashSet<>(
                subscriptions.subscribedAccountIds(NotificationSubscriptionKind.SURVEY_SUMMARIES));
        for (long accountId : contacts.activeVerifiedAdministratorIds()) {
            if (subscribed.contains(accountId))
                events.publishEvent(new SurveyEndedSummaryRequested(surveyId, accountId, endedAt, expiresAt));
        }
    }
}
