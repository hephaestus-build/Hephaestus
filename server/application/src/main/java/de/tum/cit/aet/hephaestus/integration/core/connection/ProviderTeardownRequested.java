package de.tum.cit.aet.hephaestus.integration.core.connection;

/**
 * A disconnect committed, and the provider still holds what the connection created there. The connection keeps the
 * credential of {@code credentialsGeneration} until {@link ProviderTeardown} has removed it at the provider.
 */
public record ProviderTeardownRequested(long connectionId, long workspaceId, long credentialsGeneration) {}
