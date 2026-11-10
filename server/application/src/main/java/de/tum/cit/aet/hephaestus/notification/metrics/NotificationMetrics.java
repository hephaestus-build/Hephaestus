package de.tum.cit.aet.hephaestus.notification.metrics;

public final class NotificationMetrics {

    public static final String EMAIL_DELIVERY = "notification.email.delivery";

    /** Event publications still open an hour after they were published, by listener. */
    public static final String EVENT_PUBLICATIONS_OVERDUE = "event.publications.overdue";

    private NotificationMetrics() {}
}
