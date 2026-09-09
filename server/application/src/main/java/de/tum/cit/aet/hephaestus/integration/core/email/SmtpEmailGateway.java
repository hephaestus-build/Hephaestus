package de.tum.cit.aet.hephaestus.integration.core.email;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Transport only: the workspace outbox owns recipients, content, retry and delivery history. */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@RequiredArgsConstructor
public class SmtpEmailGateway {
    private final Optional<JavaMailSender> sender;
    private final SmtpEmailProperties properties;
    private final Optional<MailProperties> transportProperties;
    private final OutboundEgressGuard egress;

    public Result send(String recipient, String subject, String body) {
        var mailSender = sender.orElse(null);
        var from = properties.from();
        if (mailSender == null || from == null || from.isBlank() || !isConfigured()) return Result.NOT_CONFIGURED;
        if (!singleAddress(recipient) || !singleAddress(from)) return Result.INVALID_ADDRESS;
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            // SMTP does not traverse the HTTP proxy: enforce Silent Mode immediately before this write.
            egress.requireDeliveryAllowed("smtp-email");
            mailSender.send(message);
            return Result.SENT;
        } catch (OutboundEgressSuppressedException suppressed) {
            return Result.SUPPRESSED;
        } catch (MailException failure) {
            // SMTP failures can contain credentials, contact addresses or provider response bodies.
            return Result.FAILED;
        }
    }

    /** Configuration readiness only, not a claim that the SMTP server is reachable. */
    public boolean isConfigured() {
        var transport = transportProperties.orElse(null);
        var from = properties.from();
        return from != null
                && !from.isBlank()
                && transport != null
                && ((transport.getHost() != null && !transport.getHost().isBlank())
                        || (transport.getJndiName() != null
                                && !transport.getJndiName().isBlank()));
    }

    private static boolean singleAddress(String value) {
        try {
            var address = new InternetAddress(value, true);
            address.validate();
            return !address.isGroup()
                    && value.equals(address.getAddress())
                    && !value.contains("\r")
                    && !value.contains("\n");
        } catch (AddressException invalid) {
            return false;
        }
    }

    public enum Result {
        SENT,
        SUPPRESSED,
        NOT_CONFIGURED,
        INVALID_ADDRESS,
        FAILED
    }
}
