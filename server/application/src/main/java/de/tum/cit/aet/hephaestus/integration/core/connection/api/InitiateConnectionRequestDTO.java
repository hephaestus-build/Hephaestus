package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Inbound payload for {@code POST /workspaces/{workspaceSlug}/connections}.
 *
 * <p>{@code userInput} is intentionally a free-form map so per-kind ConnectionStrategy
 * implementations can dictate their own field schema (e.g. Outline needs {@code server_url}; GitHub
 * needs nothing because the install URL is server-configured).
 */
public record InitiateConnectionRequestDTO(@NotNull IntegrationKind kind, Map<String, String> userInput) {}
