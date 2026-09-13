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
@WorkspaceAgnostic("Survey invitations resolve current account and workspace eligibility at delivery")
public class SurveyEmailListener {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final SurveyEmailInvitations invitations;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final EmailUnsubscribeLinks links;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(SurveyEmailRequested event) {
        if (!clock.instant().isBefore(event.expiresAt())) {
            metrics.record(EmailKind.SURVEY_INVITATION, Outcome.EXPIRED);
            return;
        }
        var invitation = invitations.eligibleInvitation(
                event.surveyId(), event.accountId(), event.reminder(), event.generation());
        if (invitation.isEmpty()) {
            metrics.record(EmailKind.SURVEY_INVITATION, Outcome.EXPIRED);
            return;
        }
        var kind = invitation.get().research()
                ? NotificationSubscriptionKind.RESEARCH_SURVEYS
                : NotificationSubscriptionKind.PRODUCT_SURVEYS;
        var token = subscriptions.unsubscribeToken(event.accountId(), kind);
        if (!subscriptions.isEnabled(event.accountId(), kind) || token.isEmpty()) {
            metrics.record(EmailKind.SURVEY_INVITATION, Outcome.UNSUBSCRIBED);
            return;
        }
        var to = contacts.activeVerifiedPrimaryEmail(event.accountId());
        if (to.isEmpty()) {
            metrics.record(EmailKind.SURVEY_INVITATION, Outcome.NO_RECIPIENT);
            return;
        }
        var rendered = renderer.render(
                EmailKind.SURVEY_INVITATION,
                Map.of(
                        "workspaceSlug",
                        invitation.get().workspaceSlug(),
                        "surveyId",
                        event.surveyId(),
                        "research",
                        invitation.get().research(),
                        "reminder",
                        event.reminder(),
                        "unsubscribeUrl",
                        links.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.SURVEY_INVITATION,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                links.url(token.get())));
        if (result.outcome() == Outcome.SENT) {
            invitations.markAccepted(
                    event.surveyId(), event.accountId(), clock.instant(), event.reminder(), event.generation());
        } else if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.SURVEY_INVITATION, event.accountId());
        }
    }
}
