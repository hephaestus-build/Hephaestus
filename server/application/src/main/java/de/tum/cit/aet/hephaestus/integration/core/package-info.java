/**
 * Shared integration lifecycle, events and item contracts. Provider adapters consume this module;
 * its code cannot depend on provider implementations.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Core",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "core",
            "core::auth-spi",
            "core::current-account",
            "core::event",
            "core::exception",
            "core::logging",
            "core::person-data",
            "core::runtime",
            "core::security",
            "core::settings-spi",
            "core::web",
            "core::webhook",
            "evidence",
            "evidence::capture",
            "workspace",
            "workspace::authorization",
            "workspace::context",
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.core;
