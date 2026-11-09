package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An instance admin's test email, to any address they name. It is sent once, while they wait for the result. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class TestEmailNotice implements EmailNotice<TestEmailNotice.TestEmail> {
    private final EmailRenderer renderer;

    /** @param adminAccountId the admin who asked; the email is erased with that account */
    record TestEmail(UUID id, String to, long adminAccountId, Instant expiresAt) {}

    @Override
    public EmailKind kind() {
        return EmailKind.TEST_MESSAGE;
    }

    @Override
    public Class<TestEmail> eventType() {
        return TestEmail.class;
    }

    @Override
    public Address address(TestEmail event) {
        return new Address(event.id().toString(), event.adminAccountId(), null, event.expiresAt());
    }

    @Override
    public Prepared prepare(TestEmail event) {
        return Prepared.send(
                EmailMessage.of(EmailKind.TEST_MESSAGE, event.to(), renderer.render(EmailKind.TEST_MESSAGE, Map.of())));
    }
}
