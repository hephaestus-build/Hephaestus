package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Security notices are addressed only to the affected native account")
public class AccountSecurityEmailListener {
    private final AccountContactQuery contacts;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountSecurityChangedEvent event) {
        if (!clock.instant().isBefore(event.occurredAt().plus(Duration.ofHours(24)))) {
            metrics.record(EmailKind.ACCOUNT_SECURITY_CHANGED, EmailDeliveryResult.Outcome.EXPIRED);
            return;
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        if (recipient.isEmpty()) {
            metrics.record(EmailKind.ACCOUNT_SECURITY_CHANGED, EmailDeliveryResult.Outcome.NO_RECIPIENT);
            return;
        }
        String change =
                switch (event.kind()) {
                    case IDENTITY_LINKED -> "A sign-in identity was linked to your account.";
                    case IDENTITY_UNLINKED -> "A sign-in identity was removed from your account.";
                    case APP_ROLE_CHANGED -> "Your instance administrator access changed.";
                };
        var rendered = renderer.render(EmailKind.ACCOUNT_SECURITY_CHANGED, Map.of("change", change));
        var result = gateway.send(EmailMessage.of(EmailKind.ACCOUNT_SECURITY_CHANGED, recipient.get(), rendered));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.ACCOUNT_SECURITY_CHANGED, event.accountId());
        }
    }
}
