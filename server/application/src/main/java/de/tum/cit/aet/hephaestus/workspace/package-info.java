/**
 * Workspace module — multi-tenancy root: workspace lifecycle, membership, context filter, scoped
 * HTTP routing, integration.spi SPI adapters.
 *
 * <p>The aggregate roots ({@code Workspace}, {@code Membership}) and the membership service live in
 * the implicit root API. Sub-packages expose narrow {@link
 * org.springframework.modulith.NamedInterface} contracts: {@code context} (tenancy ThreadLocal +
 * scoped controllers), {@code authorization} (method-security annotations), {@code settings}
 * (per-team overrides), {@code spi} (workspace-lifecycle inversion points).
 *
 * <p>Lifecycle events consumed cross-module live in {@code core.event} (e.g., {@code
 * WorkspacesInitializedEvent}) — placing them in either feature module would form a cycle with
 * {@code integration.scm} (workspace adapters already implement integration.spi SPIs).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Workspace (Tenancy)",
        allowedDependencies = {
            "core",
            "core::auth-dto",
            "core::auth-reference",
            "core::auth-spi",
            "core::auth-types",
            "core::auth-validation",
            "core::config-audit-spi",
            "core::current-account",
            "core::event",
            "core::exception",
            "core::installed-clients",
            "core::person-data",
            "core::runtime",
            "core::security",
            "core::settings-spi",
            "core::web",
            "integration.core",
            "integration.core::consumer",
            "integration.core::events",
            "integration.core::framework",
            "integration.core::handler",
            "integration.core::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.workspace;
