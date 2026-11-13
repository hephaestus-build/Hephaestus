@org.springframework.modulith.ApplicationModule(
        displayName = "Integration Identity",
        allowedDependencies = {
            "core::auth-reference",
            "core::auth-spi",
            "core::person-data",
            "core::security",
            "integration.core",
            "integration.core::spi",
            "integration.scm",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration;
