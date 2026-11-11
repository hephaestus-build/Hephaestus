package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
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
public class AccountSecurityEmailListener implements EmailNotice<AccountSecurityChangedEvent> {
    static final Duration EXPIRES_AFTER = Duration.ofHours(24);

    private final AccountContactQuery contacts;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountSecurityChangedEvent event) {
        emails.enqueue(this, event);
    }

    @Override
    public EmailKind kind() {
        return EmailKind.ACCOUNT_SECURITY_CHANGED;
    }

    @Override
    public Class<AccountSecurityChangedEvent> eventType() {
        return AccountSecurityChangedEvent.class;
    }

    @Override
    public Address address(AccountSecurityChangedEvent event) {
        return new Address(
                event.accountId() + ":" + event.kind() + ":"
                        + event.occurredAt().toEpochMilli(),
                event.accountId(),
                null,
                event.occurredAt().plus(EXPIRES_AFTER));
    }

    @Override
    public Prepared prepare(AccountSecurityChangedEvent event) {
        if (!clock.instant().isBefore(event.occurredAt().plus(EXPIRES_AFTER))) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        if (recipient.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }
        String change = switch (event.kind()) {
            case IDENTITY_LINKED -> "A sign-in identity is now linked to your account.";
            case IDENTITY_UNLINKED -> "A sign-in identity is no longer linked to your account.";
            case APP_ROLE_CHANGED -> "Your instance admin access changed.";
            case PASSKEY_CHANGED ->
                "Your passkeys or passkey protection changed. If you did not make this change, contact your instance operator.";
            case ACCOUNT_UNBLOCKED -> "An instance admin unblocked your account. You can sign in again.";
        };
        var rendered = renderer.render(EmailKind.ACCOUNT_SECURITY_CHANGED, Map.of("change", change));
        return Prepared.send(EmailMessage.of(EmailKind.ACCOUNT_SECURITY_CHANGED, recipient.get(), rendered));
    }
}
