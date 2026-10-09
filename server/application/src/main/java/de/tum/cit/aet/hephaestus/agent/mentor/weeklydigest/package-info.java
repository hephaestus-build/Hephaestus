/**
 * The weekly digest: once a week, for a developer who turned it on, the server writes, in each workspace where it is
 * available and something happened, one {@code DIGEST} entry into their Heph conversation and publishes {@link
 * de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestWritten}. Java composes it from facts
 * ({@code practices.weeklydigest}); no model writes it.
 *
 * <p>The entry's stored text and the Slack or email note that it is ready name no count and no practice: a workspace
 * owner may export Slack DMs, an email cannot be corrected, and a frozen number cannot follow a later withdrawal.
 *
 * <p>Other modules plug in through two ports: {@link
 * de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions} (the account's choice, kept by
 * the notification module) and {@link de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestTransport}
 * (whether Slack or email can carry the note).
 */
@org.springframework.modulith.NamedInterface("weekly-digest")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;
