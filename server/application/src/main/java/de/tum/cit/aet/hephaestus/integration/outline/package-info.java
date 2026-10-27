@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Outline",
        allowedDependencies = {
            "integration.chat::item",
            "integration.docs::item",
            "evidence",
            "evidence::capture",
            "core::person-data",
            // Connection registry + SPI ports (credential provider, manifest, lifecycle listener, connect strategy).
            "integration.core",
            "integration.core::framework",
            "integration.core::spi",
            "integration.core::items",
            // OutlineWebhookMessageHandler rides the JetStream consumer lane (ADR 0023 §3).
            "integration.core::handler",
            // The route endpoint publishes through the receiver; its secret derives from the routing key (ADR 0066).
            "integration.core::webhook",
            "core::webhook",
            // The scope consumer is reconciled when the stored subscription id changes.
            "integration.core::consumer",
            // ConnectionLifecycleEvent: register the webhook on activate, deregister on deactivate.
            "integration.core::events",
            // The server URL is admin-supplied: SSRF-guarded connector + ServerUrlValidator.
            "core",
            "core::security",
            // Runtime-role gate on the connect strategy.
            "core::runtime",
            // A paused or removed collection tells running Heph turns that their context changed.
            "core::event",
            // Admin services throw the shared EntityNotFoundException → 404 via the global advice.
            "core::exception",
            // A workspace PURGE cascades into a bulk delete of the mirrored rows.
            "workspace::spi",
            // Admin controllers sit behind the workspace admin guard and resolve the workspace via WorkspaceContext.
            "workspace::authorization",
            "workspace::context",
            // Document authors resolve through auth SPI ports; consent reads the exported account control enums.
            "core::auth-spi",
            "core::auth-types",
            // Outline owns outline_document and projects it to the agent one-way, so no cycle forms.
            "mentor::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.outline;
