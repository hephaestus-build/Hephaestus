package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.auth.spi.NotificationPreferencesExportQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class NotificationPreferencesExportAdapter implements NotificationPreferencesExportQuery {
    private final NotificationSubscriptionService subscriptions;

    @Override
    public Preferences preferences(long accountId) {
        var preferences = subscriptions.get(accountId);
        return new Preferences(
                preferences.productFeedback(),
                preferences.productSurveys(),
                preferences.researchSurveys(),
                preferences.productFeedbackFrequency().name(),
                preferences.workspaceAlerts(),
                preferences.surveySummaries());
    }
}
