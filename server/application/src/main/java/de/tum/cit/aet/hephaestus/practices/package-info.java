/**
 * Code-health module — AI-driven practice reviews and developer observations.
 *
 * <p>Owns the review gate ({@code review.PracticeReviewDetectionGate}) and persists results as
 * {@link de.tum.cit.aet.hephaestus.practices.model.Observation}. This module has no outbound
 * dependency on {@link de.tum.cit.aet.hephaestus.agent}: it is the {@code agent} orchestrator that
 * subscribes to {@code ScmDomainEvent}s, consults the gate here, dispatches the agent job, and
 * writes observations back through this module's named interfaces. Developer feedback lives in the
 * same module.
 *
 * <p>Sub-packages expose narrow APIs through {@link org.springframework.modulith.NamedInterface}, each declared in
 * the sub-package's {@code package-info.java}, or on the type itself where the {@code agent} module shares a single
 * review output type ({@code review-output}). A nested package is a boundary of its own to Modulith rather than
 * part of its parent's grant, so {@code review.autonomy}, which carries the practice → group → workspace resolution
 * the agent module needs at both delivery gates, declares its own. {@code DetectionReactionFirewallTest} pins
 * {@code observation.reaction} outside the detection context (ADR 0021 F-9). Internal types (controllers,
 * adapters, request DTOs) remain module-private.
 *
 * <p>Distinct bounded context from {@link de.tum.cit.aet.hephaestus.activity} (which records what developers
 * did rather than reviewing how they did it).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Practices (Code Health)")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices;
