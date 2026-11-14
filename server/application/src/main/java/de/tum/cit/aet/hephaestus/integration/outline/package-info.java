@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Outline",
        allowedDependencies = {
            // The server URL is admin-supplied: SSRF-guarded connector + ServerUrlValidator.
            "core",
            "core::auth-reference",
            // Document authors resolve through auth SPI ports; consent reads the exported account control enums.
            "core::auth-spi",
            "core::auth-types",
            // A paused or removed collection tells running Hephaestus turns that their context changed.
            "core::event",
            // Admin services throw the shared EntityNotFoundException → 404 via the global advice.
            "core::exception",
            // One subscription registration of a workspace at a time, on every server.
            "core::lock",
            "core::logging",
            "core::person-data",
            // Runtime-role gate on the connect strategy.
            "core::runtime",
            "core::security",
            "core::web",
            "core::webhook",
            "evidence",
            "evidence::capture",
            // Connection registry + SPI ports (credential provider, manifest, lifecycle listener, connect strategy).
            "integration.core",
            // The scope consumer is reconciled when the stored subscription id changes.
            "integration.core::consumer",
            // ConnectionLifecycleEvent: register the webhook on activate, deregister on deactivate.
            "integration.core::events",
            "integration.core::framework",
            // Outline webhook handling uses the PostgreSQL inbox consumer (ADR 0080).
            "integration.core::handler",
            "integration.core::items",
            "integration.core::spi",
            // The route endpoint publishes through the receiver; its secret derives from the routing key (ADR 0066).
            "integration.core::webhook",
            "integration.docs::item",
            // Outline owns outline_document and projects it to the agent one-way, so no cycle forms.
            "conversation::spi",
            // A disconnect erases the results and feedback derived from documents through the practices-owned
            // DocumentFeedbackErasure port, as Slack does for its threads (ADR 0024).
            "practices::spi",
            // Admin controllers sit behind the workspace admin guard and resolve the workspace via WorkspaceContext.
            "workspace::authorization",
            "workspace::context",
            // A workspace PURGE cascades into a bulk delete of the mirrored rows.
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.outline;
