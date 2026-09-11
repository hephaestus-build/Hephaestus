/**
 * Provider adapters and shared integration infrastructure. Cross-provider contracts live in
 * {@code integration.core.spi} and {@code integration.core.events}; family-specific contracts belong
 * to their family module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration Framework",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration;
