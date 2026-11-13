package de.tum.cit.aet.hephaestus.integration.core.connection;

/**
 * A disconnect committed, and the provider still holds what the connection created there. The connection keeps the
 * credential of {@code credentialsGeneration} for at most the retention window. Durable identity remains until removal resolves.
 */
public record ProviderTeardownRequested(long connectionId, long workspaceId, long credentialsGeneration) {}
