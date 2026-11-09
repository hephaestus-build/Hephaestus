/**
 * Email transport and rendering. {@code EmailAdapter} is the only class that talks SMTP, and only the outbox
 * executor calls it. Everything else builds an {@code EmailMessage} and enqueues it through {@code Emails}.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification.email;
