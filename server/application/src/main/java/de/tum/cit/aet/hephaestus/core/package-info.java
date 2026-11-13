/**
 * Core — cross-cutting infrastructure: logging utilities, exceptions, security primitives,
 * tenancy enforcement, runtime role configuration, proxy streaming helpers.
 *
 * <p>Root-package types ({@code LoggingUtils}, {@code WorkspaceAgnostic}) form the
 * implicit base API exposed to every feature module. Sub-packages declare narrower
 * {@link org.springframework.modulith.NamedInterface} contracts.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Core",
        allowedDependencies = {})
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core;
