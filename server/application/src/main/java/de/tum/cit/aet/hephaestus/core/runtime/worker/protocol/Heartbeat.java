package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

/**
 * Liveness frame, sent in both directions. Independent from WebSocket-level ping/pong.
 * {@code draining} is meaningful worker → hub only: it is what the hub uses to drop the worker from
 * dispatch rotation before SIGTERM finishes the drain wait. The hub answers every capacity report
 * with a non-draining heartbeat, and that echo is what keeps an idle channel inside the worker's
 * silence deadline.
 */
public record Heartbeat(boolean draining) implements WorkerControlFrame {}
