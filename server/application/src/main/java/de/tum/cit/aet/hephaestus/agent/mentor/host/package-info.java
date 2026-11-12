/**
 * The conversation sandbox's turn gateway. The shared sandbox driver claims each turn. Its TURN runner
 * ({@code turn.ts}) reads the context and sends partial answers and proposals through the run's fenced routes.
 * {@code docs/contributor/unified-pi-runtime.mdx} has the protocol.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.mentor.host;
