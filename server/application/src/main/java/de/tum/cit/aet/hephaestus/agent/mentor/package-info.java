/**
 * How Heph turns run, between the {@link de.tum.cit.aet.hephaestus.mentor} ledger and the conversation loop that runs
 * them.
 *
 * <ul>
 *   <li>{@code turn/}: admission ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission}), billing and
 *       conversational feedback delivery when a turn ends
 *       ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion}), and the lease reaper
 *       ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnLeaseReaper}).</li>
 *   <li>{@code host/}: what every worker serves the conversation loop: the turn assignments, and the endpoints the
 *       loop calls with each turn's credential ({@link de.tum.cit.aet.hephaestus.agent.mentor.host.HephTurns}).</li>
 * </ul>
 *
 * <p>Turn cost is derived from the admission-frozen {@code LlmPriceSnapshot} — the single pricing
 * authority shared with the usage ledger in {@code agent.usage}; there is no separate pricing table.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.mentor;

import org.jspecify.annotations.NullMarked;
