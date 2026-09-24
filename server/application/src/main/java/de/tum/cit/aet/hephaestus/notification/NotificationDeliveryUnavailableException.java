package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.notification.email.EmailKind;

/**
 * Thrown out of an {@code @ApplicationModuleListener} when the transport was unavailable, so the
 * event publication registry marks the publication {@code FAILED} and {@link NotificationRedeliveryJob}
 * resubmits it. Never thrown for a final outcome: those complete the publication.
 */
public class NotificationDeliveryUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotificationDeliveryUnavailableException(EmailKind kind, long accountId) {
        super("email transport unavailable for " + kind.tag() + " to account " + accountId);
    }
}
