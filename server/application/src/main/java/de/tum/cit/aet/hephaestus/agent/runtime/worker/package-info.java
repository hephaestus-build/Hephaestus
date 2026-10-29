/**
 * Worker runtime substrate: review capacity and graceful drain. Lives under {@code agent} because it coordinates the
 * agent's runtime — agent jobs flow through {@link WorkerCapacityState} and the drain coordinator owns the
 * {@code ReviewJobExecutor} lifecycle.
 */
@NamedInterface("worker-runtime")
@NullMarked
package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
