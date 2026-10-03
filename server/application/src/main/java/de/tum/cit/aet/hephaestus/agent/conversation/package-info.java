/**
 * Agent-owned SPI through which the mentor, the derived-feedback consent gate and the conversation review read
 * settled Slack conversation threads: the projected thread payload, the threads whose channel is still ACTIVE, and
 * the settled-thread candidate scan. {@code integration.slack} owns the tables and implements these interfaces, so
 * the dependency runs {@code integration.slack → agent} and a Slack column rename fails to compile inside Slack.
 * SQL against the Slack tables from {@code agent} would couple to another module's schema where the Modulith
 * import check, which reads Java imports, cannot see it.
 */
@org.springframework.modulith.NamedInterface("conversation-source")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.conversation;
