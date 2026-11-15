/**
 * How Hephaestus turns run, between the {@link de.tum.cit.aet.hephaestus.conversation} ledger and the conversation sandbox that runs
 * them.
 *
 * <ul>
 *   <li>{@code turn/}: admission ({@link de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission}), billing and
 *       conversational feedback delivery when a turn ends
 *       ({@link de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion}), and the run reaper
 *       ({@link de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper}).</li>
 *   <li>{@code host/}: what every server serves the conversation sandbox: the context and the endpoints
 *       its driver calls with the run's claim ({@link de.tum.cit.aet.hephaestus.agent.conversation.host.ConversationTurns}).</li>
 * </ul>
 *
 * <p>Turn cost is derived from the admission-frozen {@code LlmPriceSnapshot} — the single pricing
 * authority shared with the usage ledger in {@code agent.usage}; there is no separate pricing table.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.conversation;

import org.jspecify.annotations.NullMarked;
