package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A verified gateway caller: a review job attempt, a Heph turn attempt, or the conversation loop pulling turns.
 * Contains no bearer credential or frozen configuration.
 *
 * @param loop the conversation loop, which reaches only its assignment pulls
 */
record ProxyIdentity(@Nullable AttemptJwt job, @Nullable AttemptJwt turn, boolean loop) {
    ProxyIdentity {
        if (Stream.of(job, turn).filter(Objects::nonNull).count() + (loop ? 1 : 0) != 1)
            throw new IllegalArgumentException("Exactly one proxy identity is required");
    }

    static ProxyIdentity of(AttemptJwt attempt) {
        return switch (attempt.kind()) {
            case REVIEW -> new ProxyIdentity(attempt, null, false);
            case TURN -> new ProxyIdentity(null, attempt, false);
        };
    }

    static ProxyIdentity conversationLoop() {
        return new ProxyIdentity(null, null, true);
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        if (job != null) return "job:" + job.id();
        if (turn != null) return "mentor-turn:" + turn.id();
        return "conversation-loop";
    }
}
