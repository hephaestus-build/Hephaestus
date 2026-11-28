/**
 * Work that a project team submits for review: the item, its immutable versions and how they end (ADR 0165). It judges
 * nothing, and Practice Review does not depend on it. Nothing writes these records yet.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Integration · Submission",
        allowedDependencies = {"core", "core::auth-spi", "core::runtime", "project::spi"})
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.submission;
