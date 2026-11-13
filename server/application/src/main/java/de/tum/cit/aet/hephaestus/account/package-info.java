/**
 * Account module — user identity, preferences, and linked-account management.
 *
 * <p>Owns account self-service and workspace-visible account references. Core owns account choices.
 * Linked-account state is sourced from the {@code core.auth} identity links, exposed via the
 * identity-provider controller.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Account",
        allowedDependencies = {
            "core",
            "core::auth-dto",
            "core::auth-reference",
            "core::auth-spi",
            "core::auth-validation",
            "core::current-account",
            "core::exception",
            "core::runtime",
            "core::security",
            "core::web",
            "practices::spi",
            "workspace",
            "workspace::context",
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.account;
