/**
 * Conversational feedback delivery loop - the author-side seam that turns practice observations into
 * PREPARED "raise this next" markers, delivers them one-per-turn when the mentor links the observation in a chat turn,
 * and ages out the unraised remainder. Co-located under {@code agent.handler} (the delivery layer that already owns
 * the {@code practices.feedback} ledger write-orchestration, see {@code FeedbackLedgerOwnershipTest}) so the
 * cross-module dependency on the {@code practices.feedback} named interface stays within the sanctioned agent writer.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.handler.conversation;
