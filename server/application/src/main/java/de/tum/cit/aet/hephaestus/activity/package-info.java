/**
 * Activity: the activity ledger ({@code activity_event}) and, in {@code activity.overview}, the read model built
 * on it. Other modules record through {@link de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder}; ADR 0024
 * describes which reads honour a drift tombstone.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Activity",
        allowedDependencies = {
            "core",
            "core::config-audit-spi",
            "core::event",
            "core::exception",
            "core::person-data",
            "core::runtime",
            "core::security",
            "core::settings-spi",
            "core::time",
            "core::web",
            "integration.core",
            "integration.scm",
            "integration.scm::events",
            "workspace",
            "workspace::authorization",
            "workspace::context",
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.activity;
