/**
 * Account notifications and their transports, distinct from provider integrations and practice
 * feedback delivery. Domain-event listeners live here; SMTP, templates and admin verification live
 * in {@code email}. ADR 0044 owns the delivery, privacy and expiration policy.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
            // The weekly digest's email note: the event it answers, the subscription port it implements, and whether
            // a digest can be written for the account (shown in User settings).
            "agent::weekly-digest",
            "config",
            "core",
            "core::action",
            "core::auth-ratelimit",
            "core::auth-spi",
            "core::current-account",
            "core::event",
            "core::person-data",
            "core::runtime",
            "core::web",
            "integration.core",
            "integration.core::events",
            "integration.core::spi",
            "productfeedback::notification",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification;
