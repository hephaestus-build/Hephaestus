/** GitHub webhook, sync, roster and delivery adapters. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · SCM · GitHub",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "integration.scm::spi",
            "core::person-data",
            "core::auth-spi",
            "evidence",
            "integration.core",
            "integration.core::framework",
            "integration.core::events",
            "integration.core::spi",
            "integration.core::handler",
            "integration.core::oauth",
            "integration.core::consumer",
            "integration.core::webhook",
            "integration.scm",
            // RepositoryAboutToBeDeletedEvent in scm/domain/repository/events lives behind
            // a NamedInterface so ProjectIntegrityService can subscribe.
            "integration.scm::events",
            "core",
            // Runtime-role gate (@ConditionalOnServerRole) on the connection-OAuth strategy.
            "core::runtime",
            "core::webhook",
            // The signed-in account of the installation adopt, decline and claim endpoints.
            "core::security",
            "workspace",
            // An adopted installation's record forgets its purged workspace.
            "workspace::spi",
            // Activity ledger write path consumed by the Projects v2 listener under
            // project/activity/ — vendor-side records lifecycle rows through the narrow SPI.
            "activity",
            "activity::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.github;
