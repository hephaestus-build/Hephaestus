/**
 * The app's HTTP surface for Hephaestus: the developer's conversation, its messages and threads, turns, votes, and the
 * live stream. Reads come from {@link de.tum.cit.aet.hephaestus.conversation.ConversationTimeline}. A question that Hephaestus refuses
 * for the developer now is refused before the ledger stores it; the full admission runs when the turn starts
 * ({@link de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnStarter}).
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.conversation.web;
