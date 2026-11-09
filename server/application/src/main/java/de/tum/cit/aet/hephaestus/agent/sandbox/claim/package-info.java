/**
 * The single owner of each agent sandbox: one {@code agent_sandbox} row for each live sandbox, and the agent-sandbox
 * {@code SandboxClaim} that it names (ADR 0079 § Subject and sandbox). A sandbox serves one subject, the concurrency key
 * of the subject's runs. Its row is the truth, and the claim is a cache: the reaper deletes the claims of rows that no
 * longer need them, and the claims that no row names.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import org.jspecify.annotations.NullMarked;
