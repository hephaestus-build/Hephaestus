package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.util.UUID;

/**
 * Service Provider Interface for sandboxed container execution.
 *
 * <p>The {@link #execute} method blocks until the container completes, times out, or is cancelled.
 * Callers are expected to invoke it on a dedicated executor (e.g. {@code sandboxExecutor}) — never
 * on the main thread or virtual threads.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Implementations must be thread-safe. Multiple jobs may execute concurrently on different
 * threads. Cancellation via {@link #cancel} may be called from any thread.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Each call to {@link #execute} is self-contained: it creates all resources (network, container,
 * files), runs the container, collects output, and cleans up. Orphaned resources from crashes are
 * handled by {@code SandboxReconciler}.
 */
public interface SandboxManager {
    /**
     * Whether the runtime starts a sandbox now. A runtime that proves its isolation first, as Kubernetes does, answers
     * false until a proof passed, so that callers claim no work rather than fail it.
     */
    default boolean ready() {
        return true;
    }

    /**
     * Execute a sandboxed container and return the result.
     *
     * <p>Blocks the calling thread until the container exits, the timeout expires (returns with
     * {@link SandboxResult#timedOut()} = {@code true}), or an infrastructure error occurs.
     *
     * <p>Cleanup runs in a {@code finally} block regardless of outcome. Partial cleanup failures are
     * logged but do not throw — orphaned resources are handled by periodic reconciliation.
     *
     * @param spec the complete sandbox specification
     * @return the execution result (exit code, output files, logs)
     * @throws SandboxException if the sandbox infrastructure fails (Docker unreachable, container
     *     creation error, etc.)
     * @throws SandboxCancelledException if the job was cancelled via {@link #cancel}
     */
    SandboxResult execute(SandboxSpec spec) throws SandboxException;

    /**
     * Request cancellation of a running sandbox.
     *
     * <p>If a container for the given job is running, it will be stopped (SIGTERM → grace period →
     * SIGKILL). The corresponding {@link #execute} call will throw {@link SandboxCancelledException}.
     *
     * <p>Safe to call from any thread. No-op if the job is not running.
     *
     * @param jobId the job identifier
     */
    void cancel(UUID jobId);
}
