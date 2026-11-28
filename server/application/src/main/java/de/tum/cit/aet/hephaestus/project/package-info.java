/**
 * Project teams: the people a team's work belongs to, and the authority to receive it (ADR 0165). It captures and
 * judges nothing, and Practice Review does not depend on it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Project Teams",
        allowedDependencies = {"core", "core::auth-spi", "core::runtime", "workspace::spi"})
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.project;
