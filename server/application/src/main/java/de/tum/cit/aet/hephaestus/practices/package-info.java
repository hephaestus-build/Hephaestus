/**
 * Practices, practice reviews, observations and feedback.
 *
 * <p>Owns the review gate ({@code review.ReviewGate}) and persists results as
 * {@link de.tum.cit.aet.hephaestus.practices.model.Observation}. This module has no outbound
 * dependency on {@link de.tum.cit.aet.hephaestus.agent}: the {@code agent} module subscribes to
 * {@code ScmDomainEvent}s, consults the gate here, dispatches the job, and writes observations back
 * through this module's named interfaces.
 *
 * <p>Modulith treats a nested package as a boundary of its own rather than part of its parent's
 * {@link org.springframework.modulith.NamedInterface}, so {@code review.autonomy}, which the agent module
 * needs at both delivery gates, declares its own. {@code ReviewContextReactionFirewallTest} keeps
 * {@code observation.reaction} outside the review context (ADR 0021).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Practices (Code Health)")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices;
