/** GitLab webhook, sync, roster and delivery adapters. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · SCM · GitLab",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "integration.scm::spi",
            "core::person-data",
            "integration.scm::events",
            "evidence",
            "integration.core",
            "integration.core::events",
            "integration.core::spi",
            "integration.core::framework",
            "integration.core::handler",
            "integration.core::oauth",
            "integration.core::consumer",
            "integration.core::webhook",
            "integration.scm",
            "activity::spi",
            "core",
            // Runtime-role gate (@ConditionalOnServerRole) on the connection-OAuth strategy.
            "core::runtime",
            // GitLabWebhookService uses WebhookProperties; GitLabPreflightService validates URLs.
            "core::webhook",
            "core::security",
            // The project list for workspace creation is a CursorPageDTO.
            "core::web",
            "workspace",
            // GitLab workspace flows depend on the workspace named sub-surfaces.
            "workspace::context",
            "workspace::spi",
            "workspace::events",
            "workspace::dto",
            // Provider availability gated by FeatureFlagService.
            "feature",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm.gitlab;
