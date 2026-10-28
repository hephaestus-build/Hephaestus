package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The credential of one attempt: of a review job, or of a Heph turn. It names only the attempt and its workspace. The
 * attempt's row holds everything else, such as a turn's conversation and account, so a credential cannot claim them.
 *
 * @param id the {@code review_job} id, or the {@code mentor_turn} id
 * @param workDeadline when a review attempt's work ends, as its executor set it; {@code null} for a turn. The credential
 *     itself stays valid until {@code expiresAt}, so the attempt can still return its result after its work time.
 */
public record AttemptJwt(
        Kind kind,
        UUID id,
        Long workspaceId,
        int attempt,
        Set<String> scopes,
        String jti,
        Instant issuedAt,
        Instant expiresAt,
        @Nullable Instant workDeadline)
        implements WorkerJwt {

    /** What the attempt runs. */
    public enum Kind {
        REVIEW,
        TURN
    }

    public AttemptJwt {
        scopes = Set.copyOf(scopes);
        switch (kind) {
            case REVIEW -> {
                if (attempt < 0) throw new IllegalArgumentException("a review attempt must not be negative");
                // Order only: a passed work deadline still authenticates, so the attempt can return its result.
                if (workDeadline == null || workDeadline.isBefore(issuedAt) || workDeadline.isAfter(expiresAt))
                    throw new IllegalArgumentException("work deadline must not be before issue or after expiry");
            }
            case TURN -> {
                if (attempt < 1) throw new IllegalArgumentException("a claimed turn attempt counts from 1");
                if (workDeadline != null) throw new IllegalArgumentException("a turn attempt has no work deadline");
            }
        }
    }
}
