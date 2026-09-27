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
 * <p>Sub-packages expose narrow APIs via {@link org.springframework.modulith.NamedInterface}:
 * {@code model}, {@code spi}, {@code review}, {@code review.autonomy}, {@code observation}, {@code feedback},
 * and {@code observation.reaction}. {@code review.autonomy} carries the practice → group → workspace resolution
 * the agent module needs at both delivery gates; note that a nested package is a boundary of its own to
 * Modulith rather than part of its parent's grant. The latter two let the {@code agent} delivery layer write the
 * delivered-feedback ledger and read response snapshots for re-nag suppression. The persistence package keeps
 * its historical name; {@code DetectionReactionFirewallTest} pins it outside the detection context
 * (ADR 0021 F-9). Internal types (controllers, adapters, request DTOs) remain module-private.
 *
 * <p>Distinct bounded context from {@link de.tum.cit.aet.hephaestus.activity} (which records what developers
 * did rather than reviewing how they did it).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Practices (Code Health)")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices;
