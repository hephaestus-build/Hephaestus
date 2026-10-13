/**
 * The app's HTTP surface for Heph: the developer's conversation, its messages and threads, turns, votes, and the
 * live stream. Reads come from {@link de.tum.cit.aet.hephaestus.mentor.MentorTimeline}. A question that Heph refuses
 * for the developer now is refused before the ledger stores it; the full admission runs when the turn starts
 * ({@link de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter}).
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.mentor.web;
