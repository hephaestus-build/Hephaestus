/**
 * Agent-owned SPI through which the mentor and review content sources read a workspace's mirrored Outline
 * documents. {@code integration.outline} owns the table and implements it, for the reason
 * {@code agent.conversation} gives.
 */
@org.springframework.modulith.NamedInterface("documentation-source")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.documentation;
