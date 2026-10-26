package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a long-lived host: one sandboxed process that serves many requests through this worker's gateway, as the Heph
 * host does. A host gets the same isolation as a practice review: a network of its own that only this worker joins, a
 * workspace filled from its files through a gateway session, and the hardened container configuration. On an internal
 * network it reaches nothing but this worker's gateway; a worker outside Docker gives it a network with internet
 * access instead. It gets no review workspace.
 *
 * <p>The sandbox sets the gateway variables {@code LLM_PROXY_URL}, {@code SANDBOX_RUNTIME_URL} and
 * {@code LLM_PROXY_TOKEN}. The token opens the host's gateway session.
 */
public interface HostSandbox {

    /**
     * @param files the workspace, by path relative to the workspace root
     * @param environment the variables besides the gateway's
     * @param command the container command
     * @param limits the container's memory, CPU and process limits. A host runs until it is removed, so
     *     {@code maxRuntime} bounds only how long filling its workspace may take
     */
    record HostSpec(
            Map<String, byte[]> files, Map<String, String> environment, List<String> command, ResourceLimits limits) {
        public HostSpec {
            files = Map.copyOf(files);
            environment = Map.copyOf(environment);
            command = List.copyOf(command);
        }
    }

    /** A started host. Its id names its gateway session. */
    record Host(UUID id) {}

    /**
     * Whether the runtime starts a sandbox now. A runtime that proves its isolation first, as Kubernetes does, answers
     * false until a proof passed, so that callers claim no work rather than fail it.
     */
    default boolean ready() {
        return true;
    }

    /** Starts a host, and returns once its process runs. */
    Host launch(HostSpec spec) throws IOException;

    boolean isRunning(Host host);

    /** Removes a host and what it ran on. Best effort: {@code SandboxCreator} says what cleanup removes later. */
    void remove(Host host);
}
