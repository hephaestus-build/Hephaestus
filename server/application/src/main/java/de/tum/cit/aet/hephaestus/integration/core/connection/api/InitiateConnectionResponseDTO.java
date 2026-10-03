package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import java.net.URI;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Flat response for {@code POST /workspaces/{workspaceSlug}/connections}.
 *
 * <p>Two outcomes, distinguished by {@code type}:
 * <ul>
 *   <li>{@code REDIRECT} — OAuth / App-install flows (GitHub, Slack). {@code vendorUrl} is the
 *       URL to bounce the browser to; the signed OAuth state is already embedded in it.</li>
 *   <li>{@code LINKED} — inline-credential flows (GitLab PAT). {@code connectionId} is the
 *       newly-created Connection; no further round-trip is needed.</li>
 * </ul>
 *
 * <p>Deliberately a flat record (nullable per-variant fields) rather than a sealed
 * {@code oneOf} hierarchy: a discriminated union serialises to OpenAPI {@code oneOf +
 * discriminator}, which the webapp's hey-api react-query transformer mishandles. A flat shape
 * round-trips cleanly through code generation, so the webapp consumes the generated client
 * instead of a hand-rolled fetch wrapper.
 */
public record InitiateConnectionResponseDTO(
        @NonNull Type type,
        @Nullable URI vendorUrl,
        @Nullable Long connectionId) {
    public enum Type {
        REDIRECT,
        LINKED,
    }

    public static InitiateConnectionResponseDTO redirect(URI vendorUrl) {
        return new InitiateConnectionResponseDTO(Type.REDIRECT, vendorUrl, null);
    }

    public static InitiateConnectionResponseDTO linked(Long connectionId) {
        return new InitiateConnectionResponseDTO(Type.LINKED, null, connectionId);
    }
}
