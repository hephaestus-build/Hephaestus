package de.tum.cit.aet.hephaestus.notification.email;

import java.time.Instant;

/** A test notice whose event is the finished message, so a test drives the adapter with any message it builds. */
final class DirectEmailNotice implements EmailNotice<DirectEmailNotice.Direct> {
    record Direct(EmailMessage message) {}

    @Override
    public EmailKind kind() {
        return EmailKind.TEST_MESSAGE;
    }

    @Override
    public Class<Direct> eventType() {
        return Direct.class;
    }

    @Override
    public Address address(Direct event) {
        return new Address(event.message().to(), 1L, null, Instant.MAX);
    }

    @Override
    public Prepared prepare(Direct event) {
        return Prepared.send(event.message());
    }
}
