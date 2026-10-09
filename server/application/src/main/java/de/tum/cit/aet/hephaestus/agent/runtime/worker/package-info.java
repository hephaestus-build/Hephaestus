/**
 * Worker runtime substrate: capacity reporting, graceful drain, WSS control-channel client,
 * health indicator. Lives under {@code agent} because it coordinates the agent's runtime — agent
 * jobs flow through {@link WorkerCapacityState} and the drain coordinator owns the
 * {@code AgentJobExecutor} lifecycle. Wire protocol records are shared with the hub via
 * {@code core.runtime.worker.protocol}.
 */
@org.springframework.modulith.NamedInterface("worker-runtime")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.agent.runtime.worker;
