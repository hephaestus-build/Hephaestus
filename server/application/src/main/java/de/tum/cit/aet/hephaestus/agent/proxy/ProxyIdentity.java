package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A verified sandbox caller: a review job attempt, a Heph turn attempt, or a Heph host's gateway session. Contains no
 * bearer credential or frozen configuration.
 *
 * @param hostSession the gateway session of a Heph host, which reaches only its own command channel
 */
record ProxyIdentity(
        @Nullable AttemptJwt job,
        @Nullable AttemptJwt turn,
        @Nullable UUID hostSession) {
    ProxyIdentity {
        if (Stream.of(job, turn, hostSession).filter(Objects::nonNull).count() != 1)
            throw new IllegalArgumentException("Exactly one proxy identity is required");
    }

    static ProxyIdentity of(AttemptJwt attempt) {
        return switch (attempt.kind()) {
            case REVIEW -> new ProxyIdentity(attempt, null, null);
            case TURN -> new ProxyIdentity(null, attempt, null);
        };
    }

    static ProxyIdentity hostSession(UUID session) {
        return new ProxyIdentity(null, null, session);
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        if (job != null) return "job:" + job.id();
        if (turn != null) return "mentor-turn:" + turn.id();
        return "gateway-session:" + hostSession;
    }
}
