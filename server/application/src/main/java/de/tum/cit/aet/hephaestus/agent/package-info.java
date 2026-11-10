/**
 * Agent module — LLM-driven sandbox runtime that executes practice review and mentor turns.
 *
 * <p>A review's job waits on its agent run (ADR 0079): the server dispatches each due run's
 * sandbox, and the sandbox claims and runs it through the gateway.
 */
@ApplicationModule(displayName = "Agent")
@NullMarked
package de.tum.cit.aet.hephaestus.agent;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
