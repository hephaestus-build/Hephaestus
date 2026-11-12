package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Duration;
import java.util.List;

/**
 * The tools Pods of conversation turns: each runs only the tools program of tool protocol v1, on TCP, and its host
 * dials it. The server never runs a command in one; it starts, lists and removes them.
 */
public interface ToolsPods {

    /** The port that a tools Pod's tools program listens on. */
    int PORT = 7100;

    /** The name of the tools Pod's Job for this id. */
    static String name(String sandboxId) {
        return "hephaestus-tools-" + sandboxId;
    }

    /**
     * Starts one tools Pod and waits until its tools program runs.
     *
     * @param sandboxId a DNS label that names the Pod among the installation's
     * @param nonce what the Pod's {@code hello} returns
     * @throws de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException when the tools quota has no room
     */
    Started create(String sandboxId, String nonce, ResourceLimits limits);

    /** Removes the tools Pod and every process in it; a Pod that is already gone is no error. */
    void remove(String name);

    /** The tools Pods of the installation, so that a Pod that no row names goes. */
    List<Listed> list();

    /** Whether the runtime's isolation is proven, so that a tools Pod may start. */
    default boolean ready() {
        return true;
    }

    /**
     * A started tools Pod: where its host dials it, and the nonce that its {@code hello} must return, so that a process
     * on a reused address cannot take the connection.
     */
    record Started(String id, String host, int port, String nonce) {}

    /** A tools Pod's Job, and how long ago it was created. */
    record Listed(String name, Duration age) {}
}
