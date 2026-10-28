package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.AttemptJwt;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A verified sandbox caller: a review job attempt, a Heph turn attempt, or a Heph host's gateway session. Contains no
 * bearer credential or frozen configuration.
 *
 * @param workerId the worker that runs the job or the turn attempt; {@code null} for a host session
 * @param hostSession the gateway session of a Heph host, which reaches only its own command channel
 */
record ProxyIdentity(
        @Nullable AttemptJwt job,
        @Nullable AttemptJwt turn,
        @Nullable UUID hostSession,
        @Nullable String workerId) {
    ProxyIdentity {
        if (Stream.of(job, turn, hostSession).filter(Objects::nonNull).count() != 1)
            throw new IllegalArgumentException("Exactly one proxy identity is required");
        if (hostSession == null) Objects.requireNonNull(workerId, "workerId");
    }

    static ProxyIdentity of(AttemptJwt attempt, String workerId) {
        return switch (attempt.kind()) {
            case REVIEW -> new ProxyIdentity(attempt, null, null, workerId);
            case TURN -> new ProxyIdentity(null, attempt, null, workerId);
        };
    }

    static ProxyIdentity hostSession(UUID session) {
        return new ProxyIdentity(null, null, session, null);
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        if (job != null) return "job:" + job.id();
        if (turn != null) return "mentor-turn:" + turn.id();
        return "gateway-session:" + hostSession;
    }
}
