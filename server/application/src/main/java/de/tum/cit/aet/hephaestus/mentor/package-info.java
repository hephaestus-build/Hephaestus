/**
 * Mentor module: the Heph conversation ledger. It owns the conversations, their append-only entries and the turns
 * that answer them, and the domain rules on them. It knows nothing about how a turn runs.
 *
 * <h2>Split with {@code agent/mentor/}</h2>
 * Admission, billing, conversational feedback delivery and the runtime that runs a turn live in
 * {@link de.tum.cit.aet.hephaestus.agent.mentor}, which calls this module. The split mirrors
 * {@code practices/} ↔ {@code agent/practice/}.
 *
 * <h2>Key entry points</h2>
 * <ul>
 *   <li>{@link de.tum.cit.aet.hephaestus.mentor.MentorConversationService}: main conversations and threads</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.mentor.MentorEntryService}: appends, votes, edits, redaction, search</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.mentor.MentorTurnService}: submission, claim, lease, end, stop</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.mentor.MentorContextPathBuilder}: the Pi session path of a turn</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents}: what changed, published inside the write</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Mentor")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.mentor;
