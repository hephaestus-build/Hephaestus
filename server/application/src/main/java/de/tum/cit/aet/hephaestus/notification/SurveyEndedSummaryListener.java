package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryMetrics;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.SurveyEmailInvitations;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Survey end summaries address currently eligible instance administrators")
public class SurveyEndedSummaryListener {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final SurveyEmailInvitations invitations;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final EmailUnsubscribeLinks links;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(SurveyEndedSummaryRequested event) {
        if (!clock.instant().isBefore(event.expiresAt())) {
            metrics.record(EmailKind.SURVEY_ENDED_SUMMARY, Outcome.EXPIRED);
            return;
        }
        var summary = invitations.endedSummary(event.surveyId());
        if (summary.isEmpty() || !summary.get().endedAt().equals(event.endedAt())) {
            metrics.record(EmailKind.SURVEY_ENDED_SUMMARY, Outcome.EXPIRED);
            return;
        }
        var token = subscriptions.unsubscribeToken(event.accountId(), NotificationSubscriptionKind.SURVEY_SUMMARIES);
        if (!subscriptions.isEnabled(event.accountId(), NotificationSubscriptionKind.SURVEY_SUMMARIES)
                || token.isEmpty()) {
            metrics.record(EmailKind.SURVEY_ENDED_SUMMARY, Outcome.UNSUBSCRIBED);
            return;
        }
        var to = contacts.activeVerifiedAdministratorEmail(event.accountId());
        if (to.isEmpty()) {
            metrics.record(EmailKind.SURVEY_ENDED_SUMMARY, Outcome.NO_RECIPIENT);
            return;
        }
        var counts = summary.get();
        var rendered = renderer.render(
                EmailKind.SURVEY_ENDED_SUMMARY,
                Map.of(
                        "invited",
                        counts.invited(),
                        "responded",
                        counts.responded(),
                        "declined",
                        counts.declined(),
                        "unsubscribeUrl",
                        links.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.SURVEY_ENDED_SUMMARY,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                links.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.SURVEY_ENDED_SUMMARY, event.accountId());
        }
    }
}
