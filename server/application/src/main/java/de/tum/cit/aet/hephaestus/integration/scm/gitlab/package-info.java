/** GitLab webhook, sync, roster and delivery adapters. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · SCM · GitLab",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "activity::spi",
            "core",
            "core::logging",
            "core::person-data",
            // Runtime-role gate (@ConditionalOnServerRole) on the connection-OAuth strategy.
            "core::runtime",
            "core::security",
            // The project list for workspace creation is a CursorPageDTO; hooks register at PublicApiUrls.
            "core::web",
            // Route credentials and token rotation use WebhookProperties; GitLabPreflightService validates URLs.
            "core::webhook",
            "evidence",
            "integration.core",
            "integration.core::consumer",
            "integration.core::events",
            "integration.core::framework",
            "integration.core::handler",
            "integration.core::spi",
            "integration.core::webhook",
            "integration.scm",
            "integration.scm::events",
            "integration.scm::spi",
            "workspace",
            // GitLab workspace flows depend on the workspace named sub-surfaces.
            "workspace::context",
            "workspace::dto",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.gitlab;
