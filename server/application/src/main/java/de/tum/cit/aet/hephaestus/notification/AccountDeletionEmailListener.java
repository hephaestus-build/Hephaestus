package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionScheduledEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/** Account-deletion confirmations; delivery and expiration policy are documented in ADR 0044. */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Account-lifecycle email is account-scoped, not tenant data")
public class AccountDeletionEmailListener implements EmailNotice<AccountDeletionScheduledEvent> {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionEmailListener.class);
    private static final DateTimeFormatter PURGE_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", EmailRenderer.LOCALE);

    private final Clock clock;
    private final AccountContactQuery contacts;
    private final EmailRenderer renderer;
    private final Emails emails;

    public AccountDeletionEmailListener(
            AccountContactQuery contacts, EmailRenderer renderer, Emails emails, Clock clock) {
        this.clock = clock;
        this.contacts = contacts;
        this.renderer = renderer;
        this.emails = emails;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountDeletionScheduledEvent event) {
        emails.enqueue(this, event);
    }

    @Override
    public EmailKind kind() {
        return EmailKind.ACCOUNT_DELETION_SCHEDULED;
    }

    @Override
    public Class<AccountDeletionScheduledEvent> eventType() {
        return AccountDeletionScheduledEvent.class;
    }

    @Override
    public Address address(AccountDeletionScheduledEvent event) {
        return new Address(
                event.accountId() + ":" + event.purgeAfter().toEpochMilli(),
                event.accountId(),
                null,
                event.purgeAfter());
    }

    @Override
    public Prepared prepare(AccountDeletionScheduledEvent event) {
        if (!clock.instant().isBefore(event.purgeAfter())) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        Optional<String> to = contacts.verifiedPrimaryEmail(event.accountId());
        if (to.isEmpty()) {
            log.info(
                    "email: account deletion confirmation withheld, no verified address accountId={}",
                    event.accountId());
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }
        var rendered = renderer.render(
                EmailKind.ACCOUNT_DELETION_SCHEDULED,
                Map.of("purgeAfter", PURGE_DATE.format(event.purgeAfter().atZone(ZoneOffset.UTC))));
        return Prepared.send(EmailMessage.of(EmailKind.ACCOUNT_DELETION_SCHEDULED, to.get(), rendered));
    }
}
