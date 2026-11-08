package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The tools Pods of conversation turns: each runs only the tools program of tool protocol v1, on TCP, and its host
 * dials it. The worker never runs a command in one; it starts, lists and removes them.
 */
public interface ToolsPods {

    /** The port that a tools Pod's tools program listens on. */
    int PORT = 7100;

    /**
     * Starts one tools Pod and waits until its tools program runs.
     *
     * @param sandboxId a DNS label that names the Pod among this worker's
     * @throws de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException when the tools quota has no room
     */
    Started create(String sandboxId, ResourceLimits limits);

    /** Removes the tools Pod and every process in it; a Pod that is already gone is no error. */
    void remove(String id);

    /** The tools Pods of every worker, so that a worker removes what it no longer serves. */
    List<Listed> list();

    /** The name this worker gives its tools Pods, or null when it cannot name itself. */
    @Nullable
    String workerInstance();

    /** Whether the runtime's isolation is proven, so that a tools Pod may start. */
    default boolean ready() {
        return true;
    }

    /**
     * A started tools Pod: where its host dials it, and the nonce that its {@code hello} must return, so that a process
     * on a reused address cannot take the connection.
     */
    record Started(String id, String host, int port, String nonce) {}

    record Listed(String id, String sandboxId, String workerInstance, boolean workerGone, boolean ended) {}
}
