/**
 * Outline vendor adapter: mirrors allow-listed Outline collections into local documents and projects them
 * into the agent's context so design docs and decision records reach practice reviews. A closed Modulith
 * module — the agent reads through its own {@code agent::documentation-source} SPI, which this module
 * implements. It records document signals into the shared ledger but produces no observations
 * of its own — a review of a document is composed in the agent module, off that ledger.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Outline",
        allowedDependencies = {
            // Connection registry + SPI ports (credential provider, manifest, lifecycle listener, connect strategy).
            "integration.core",
            "integration.core::spi",
            // OutlineWebhookMessageHandler rides the JetStream consumer lane (ADR 0023 §3).
            "integration.core::handler",
            // The scope consumer is reconciled when the stored subscription id changes.
            "integration.core::consumer",
            // ConnectionLifecycleEvent: register the webhook on activate, deregister on deactivate.
            "integration.core::events",
            // The server URL is admin-supplied: SSRF-guarded connector + ServerUrlValidator.
            "core",
            "core::security",
            // Runtime-role gate on the connect strategy.
            "core::runtime",
            // Admin services throw the shared EntityNotFoundException → 404 via the global advice.
            "core::exception",
            // A workspace PURGE cascades into a bulk delete of the mirrored rows.
            "workspace::spi",
            // Admin controllers sit behind the workspace admin guard and resolve the workspace via WorkspaceContext.
            "workspace::authorization",
            "workspace::context",
            // Document authors resolve through auth SPI ports only — never core.auth or SCM domain types.
            "core::auth-spi",
            // Outline owns outline_document and projects it to the agent one-way, so no cycle forms.
            "agent::documentation-source",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.outline;
