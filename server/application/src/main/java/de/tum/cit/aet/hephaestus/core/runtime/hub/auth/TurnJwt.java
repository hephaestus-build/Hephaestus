package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The credential of one Heph turn attempt, the sibling of {@link JobJwt}: the Heph host presents it for the turn's
 * model calls and for the worker endpoints the turn's tools call. It names the developer and the conversation the
 * attempt answers, so no tool of one turn can act for another.
 */
public record TurnJwt(
        UUID turnId,
        Long workspaceId,
        Long userId,
        UUID conversationId,
        int attempt,
        Set<String> scopes,
        String jti,
        Instant issuedAt,
        Instant expiresAt)
        implements WorkerJwt {
    public TurnJwt {
        scopes = Set.copyOf(scopes);
        if (attempt < 1) throw new IllegalArgumentException("a claimed turn attempt counts from 1");
    }
}
