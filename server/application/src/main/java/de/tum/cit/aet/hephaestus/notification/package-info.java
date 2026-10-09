/**
 * Account notifications and their transports, distinct from provider integrations and practice
 * feedback delivery. Domain-event listeners live here; SMTP, templates and admin verification live
 * in {@code email}. ADR 0044 owns the delivery, privacy and expiration policy.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            "core::person-data",
            "core",
            "core::current-account",
            "core::runtime",
            "core::auth-spi",
            "core::auth-ratelimit",
            "core::event",
            "config",
            "integration.core",
            "integration.core::events",
            "integration.core::spi",
            "productfeedback::notification",
            // The weekly digest's email note: the event it answers, the subscription port it implements, and whether
            // a digest can be written for the account (shown in User settings).
            "agent::weekly-digest",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification;
