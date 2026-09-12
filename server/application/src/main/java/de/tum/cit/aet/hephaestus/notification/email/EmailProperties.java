package de.tum.cit.aet.hephaestus.notification.email;

import jakarta.validation.constraints.Email;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Sender identity for every email Hephaestus sends. The SMTP transport itself is Boot's
 * {@code spring.mail.*}; this record only says who the email is from.
 *
 * @param from the envelope and header sender address; required once {@code spring.mail.host} is set,
 *     and the relay must be allowed to send for its domain (SPF/DKIM are the operator's)
 * @param fromName the display name shown next to {@code from}
 * @param replyTo where a human reply lands; blank means replies go to {@code from}
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.email")
public record EmailProperties(
        @Nullable @Email String from,
        @DefaultValue("Hephaestus") String fromName,
        @Nullable @Email String replyTo) {

    public boolean hasFrom() {
        return from != null && !from.isBlank();
    }

    public boolean hasReplyTo() {
        return replyTo != null && !replyTo.isBlank();
    }
}
