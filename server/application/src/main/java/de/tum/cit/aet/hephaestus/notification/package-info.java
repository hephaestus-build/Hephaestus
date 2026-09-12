/**
 * Account notifications and their transports, distinct from provider integrations and practice
 * feedback delivery. Domain-event listeners live here; SMTP, templates and admin verification live
 * in {@code email}. ADR 0044 owns the delivery, privacy and expiration policy.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            "core",
            "core::current-account",
            "core::runtime",
            "core::auth-spi",
            "core::event",
            "config",
            "integration.core",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification;
