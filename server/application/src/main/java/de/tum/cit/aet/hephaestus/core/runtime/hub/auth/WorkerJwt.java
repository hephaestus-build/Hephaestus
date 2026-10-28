package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import java.time.Instant;

public sealed interface WorkerJwt permits WorkerSessionJwt, AttemptJwt {
    String jti();

    Instant issuedAt();

    Instant expiresAt();
}
