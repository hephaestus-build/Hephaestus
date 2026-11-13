/**
 * Observability module — request correlation, replica identity, context propagation and the structured log
 * customizer.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Observability",
        allowedDependencies = {
            "core::web",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.observability;
