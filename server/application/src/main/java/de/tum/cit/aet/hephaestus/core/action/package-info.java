/**
 * The outbox: every effect on the outside world is one {@code agent_action} row, and one executor on every server
 * replica carries it out. A module that acts enqueues an {@link de.tum.cit.aet.hephaestus.core.action.ActionRequest}
 * through {@link de.tum.cit.aet.hephaestus.core.action.AgentActions} in its own transaction, and implements one
 * {@link de.tum.cit.aet.hephaestus.core.action.ActionAdapter} for its kind. The domain lifecycle (a feedback, a
 * filing) stays on the domain row; this package owns only the delivery of the effect. See
 * {@code docs/contributor/outbox.md}.
 */
@org.springframework.modulith.NamedInterface("action")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.action;
