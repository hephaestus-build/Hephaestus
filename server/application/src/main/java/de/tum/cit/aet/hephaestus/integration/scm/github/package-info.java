/** GitHub webhook, sync, roster and delivery adapters. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · SCM · GitHub",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "activity::spi",
            "core",
            "core::auth-reference",
            "core::auth-spi",
            "core::logging",
            "core::person-data",
            // Runtime-role gate (@ConditionalOnServerRole) on the connection-OAuth strategy.
            "core::runtime",
            // The signed-in account of the installation adopt, decline and claim endpoints.
            "core::security",
            "core::webhook",
            "evidence",
            "integration.core",
            "integration.core::consumer",
            "integration.core::events",
            "integration.core::framework",
            "integration.core::handler",
            "integration.core::spi",
            "integration.scm",
            // RepositoryAboutToBeDeletedEvent in scm/domain/repository/events lives behind
            // a NamedInterface so ProjectIntegrityService can subscribe.
            "integration.scm::events",
            "integration.scm::spi",
            "workspace",
            // An adopted installation's record forgets its purged workspace.
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.github;
