package de.tum.cit.aet.hephaestus.notification.email;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * One email ready to send: a rendered {@link RenderedEmail} addressed to one recipient. The
 * recipient is a bare address, never an entity, so rendering and transport stay free of the
 * persistence context ({@code NotificationArchitectureTest}).
 */
public record EmailMessage(
        EmailKind kind,
        String to,
        String subject,
        String text,
        String html,
        @Nullable String unsubscribeUrl) {

    public EmailMessage {
        if (kind.optional() && unsubscribeUrl == null) {
            throw new IllegalArgumentException("Optional email requires an unsubscribe URL");
        }
        if (unsubscribeUrl != null) {
            URI uri = URI.create(unsubscribeUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "Unsubscribe URL must be an absolute HTTP(S) URL without credentials or fragment");
            }
        }
    }

    public static EmailMessage of(EmailKind kind, String to, RenderedEmail rendered) {
        return new EmailMessage(kind, to, rendered.subject(), rendered.text(), rendered.html(), null);
    }
}
