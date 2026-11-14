/**
 * Agent module — LLM-driven sandbox runtime that executes practice review and conversation turns.
 *
 * <p>A review's job waits on its agent run (ADR 0079): the server dispatches each due run's
 * sandbox, and the sandbox claims and runs it through the gateway.
 */
@ApplicationModule(
        displayName = "Agent",
        allowedDependencies = {
            "core",
            "core::action",
            "core::auth-ratelimit",
            "core::auth-reference",
            "core::auth-spi",
            "core::auth-types",
            "core::config-audit-spi",
            "core::exception",
            "core::lock",
            "core::logging",
            "core::person-data",
            "core::proxy",
            "core::release",
            "core::runtime",
            "core::security",
            "core::web",
            "evidence",
            "evidence::capture",
            "integration.core",
            "integration.core::framework",
            "integration.core::items",
            "integration.core::review-metrics",
            "integration.core::spi",
            "conversation",
            "conversation::spi",
            "practices",
            "practices::model",
            "practices::result",
            "practices::review",
            "practices::spi",
            "practices::weekly-digest",
            "productfeedback::drafting",
            "workspace",
            "workspace::authorization",
            "workspace::context",
            "workspace::spi",
        })
@NullMarked
package de.tum.cit.aet.hephaestus.agent;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
