package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** A verified model-gateway caller. Its token or sandbox run names its attempt. */
record ProxyIdentity(@Nullable ProxyAttempt job, @Nullable ProxyAttempt turn) {
    ProxyIdentity {
        if (Stream.of(job, turn).filter(Objects::nonNull).count() != 1)
            throw new IllegalArgumentException("Exactly one proxy identity is required");
    }

    static ProxyIdentity of(AttemptJwt token) {
        return of(ProxyAttempt.of(token));
    }

    static ProxyIdentity of(ProxyAttempt attempt) {
        return switch (attempt.caller()) {
            case REVIEW -> new ProxyIdentity(attempt, null);
            case TURN -> new ProxyIdentity(null, attempt);
        };
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        if (job != null) return "job:" + job.id();
        return "mentor-turn:" + Objects.requireNonNull(turn).id();
    }
}
