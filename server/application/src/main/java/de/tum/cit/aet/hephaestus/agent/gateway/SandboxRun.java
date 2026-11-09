package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;

/**
 * A sandbox's call for one of its subject's runs: the route's run, named again by {@code X-Agent-Run}, under the claim
 * that {@code X-Agent-Epoch} names. {@link SandboxRunArgumentResolver} answers it only for a run of the sandbox's own
 * workspace and subject. It checks no lease: a write calls {@code AgentRuns.requireCurrent(fence())} in its own
 * transaction, so the lease stays held for that write.
 *
 * @param attempt the attempts the run spent before this claim, for the backoff of a hand-on
 */
public record SandboxRun(AgentSandboxes.LiveSandbox sandbox, AgentRunFence fence, AgentRunKind kind, int attempt) {}
