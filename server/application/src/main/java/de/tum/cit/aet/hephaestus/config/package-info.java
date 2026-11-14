/**
 * Shared Spring configuration. Other modules use its public configuration properties and executor names.
 * A configuration that belongs to one module stays with that module. ADR 0099 keeps this module closed.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Config (shared kernel)",
        allowedDependencies = {})
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.config;
