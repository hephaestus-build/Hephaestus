package de.tum.cit.aet.hephaestus.notification.email;

/**
 * One email ready to send: a rendered {@link RenderedEmail} addressed to one recipient. The
 * recipient is a bare address, never an entity, so rendering and transport stay free of the
 * persistence context ({@code NotificationArchitectureTest}).
 */
public record EmailMessage(EmailKind kind, String to, String subject, String text, String html) {

    public static EmailMessage of(EmailKind kind, String to, RenderedEmail rendered) {
        return new EmailMessage(kind, to, rendered.subject(), rendered.text(), rendered.html());
    }
}
