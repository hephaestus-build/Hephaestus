/**
 * SCM domain and persistence shared by the GitHub and GitLab adapters. Provider-neutral synchronization
 * lives in {@code scm.sync}; provider-specific behavior belongs in {@code scm.github} or {@code scm.gitlab}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "SCM",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {
            "config",
            "core",
            "core::auth-reference",
            "core::auth-spi",
            "core::auth-types",
            "core::current-account",
            "core::event",
            "core::exception",
            "core::logging",
            "core::person-data",
            "core::runtime",
            "core::security",
            "core::web",
            "evidence",
            "evidence::capture",
            "integration.core",
            "integration.core::consumer",
            "integration.core::framework",
            "integration.core::items",
            "integration.core::review-metrics",
            "integration.core::spi",
            "integration.scm.github",
            "integration.scm.gitlab",
            "workspace",
            "workspace::authorization",
            "workspace::context",
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.scm;
