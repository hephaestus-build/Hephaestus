/**
 * How Heph turns run, between the {@link de.tum.cit.aet.hephaestus.mentor} ledger and the Heph host that runs them.
 *
 * <ul>
 *   <li>{@code turn/}: admission ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission}), billing and
 *       conversational feedback delivery when a turn ends
 *       ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion}), and the lease reaper
 *       ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnLeaseReaper}).</li>
 *   <li>{@code host/}: the worker's Heph host, the one long-lived container that runs this worker's turns, and the
 *       endpoints it calls with each turn's credential
 *       ({@link de.tum.cit.aet.hephaestus.agent.mentor.loop.ConversationAssignments}).</li>
 * </ul>
 *
 * <p>Turn cost is derived from the admission-frozen {@code LlmPriceSnapshot} — the single pricing
 * authority shared with the usage ledger in {@code agent.usage}; there is no separate pricing table.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.mentor;

import org.jspecify.annotations.NullMarked;
