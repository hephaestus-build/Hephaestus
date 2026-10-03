/**
 * Job-type dispatch ({@code handler.spi} and the {@code *ReviewHandler}s) and the delivery layer that renders
 * observations into feedback and posts it. {@code FeedbackLedgerRecorder} is the only writer of the
 * {@code practices.feedback} ledger ({@code FeedbackLedgerOwnershipTest}). Delivery may read reactions; the review
 * context in {@code agent.context.providers} may not ({@code ReviewContextReactionFirewallTest}).
 *
 * <p>Both depend on {@code agent.job} and on the {@code practices} and {@code integration.scm} named interfaces;
 * moving delivery into {@code integration} would create a Modulith cycle.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.handler;
