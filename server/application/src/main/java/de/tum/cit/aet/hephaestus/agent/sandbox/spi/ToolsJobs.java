package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Credential-free tools Jobs, one for each attempt identity: its lease and the context it contains. */
public interface ToolsJobs {
    record Endpoint(String host, int port, String nonce) {}

    /** A tools Job's whole identity: the attempt, its lease and the context generation it contains. */
    record Binding(long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration) {}

    /**
     * The nonce-bound endpoint of the tools Job of exactly this identity. The caller holds the attempt's live lease and
     * its current context generation; the Job knows neither. Every other Job of the turn is deleted first, and this
     * waits until it is gone. A discarded Job is never reused.
     */
    Endpoint acquire(
            long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration, Instant deadline);

    /**
     * Deletes only this binding, including all its processes, with Foreground propagation. Repeated calls are safe;
     * a delayed discard of an old context cannot delete a replacement Job.
     */
    void discard(long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration);

    /**
     * Discards every tools Job of this release whose identity is none of {@code live}, as {@link #discard} does each.
     * Any worker may call it with what PostgreSQL holds live, at any time and on many workers at once; a binding that
     * {@code live} names is never touched.
     */
    void retainOnly(Collection<Binding> live);
}
